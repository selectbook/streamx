/**
 * Copyright (c) 2019 The StreamX Project
 * <p>
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.streamxhub.flink.core.sink


import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.sink.{RichSinkFunction, SinkFunction}
import java.sql._
import java.util.concurrent.atomic.AtomicLong
import java.util.{Properties, Timer, TimerTask}

import com.streamxhub.common.conf.ConfigConst._
import com.streamxhub.common.util.{ConfigUtils, Logger, MySQLUtils}
import com.streamxhub.flink.core.StreamingContext
import org.apache.flink.api.common.io.RichOutputFormat
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.datastream.DataStreamSink
import org.apache.flink.streaming.api.environment.CheckpointConfig
import org.apache.flink.streaming.api.scala.DataStream

import scala.collection.JavaConversions._
import scala.collection.Map

object JdbcSink {

  /**
   * @param ctx      : StreamingContext
   * @param instance : MySQL的实例名称(用于区分多个不同的MySQL实例...)
   * @return
   */
  def apply(@transient ctx: StreamingContext,
            overwriteParams: Map[String, String] = Map.empty[String, String],
            parallelism: Int = 0,
            name: String = null,
            uid: String = null)(implicit instance: String = ""): JdbcSink = new JdbcSink(ctx, overwriteParams, parallelism, name, uid)

}

class JdbcSink(@transient ctx: StreamingContext,
               overwriteParams: Map[String, String] = Map.empty[String, String],
               parallelism: Int = 0,
               name: String = null,
               uid: String = null)(implicit instance: String = "") extends Sink with Logger {

  //每隔10s进行启动一个检查点
  ctx.enableCheckpointing(10000)
  //设置模式为：exactly_one，仅一次语义
  ctx.getCheckpointConfig.setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE)
  //确保检查点之间有1s的时间间隔【checkpoint最小间隔】
  ctx.getCheckpointConfig.setMinPauseBetweenCheckpoints(1000)
  //检查点必须在10s之内完成，或者被丢弃【checkpoint超时时间】
  ctx.getCheckpointConfig.setCheckpointTimeout(10000)
  //同一时间只允许进行一次检查点
  ctx.getCheckpointConfig.setMaxConcurrentCheckpoints(1)
  //被cancel会保留Checkpoint数据
  ctx.getCheckpointConfig.enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION)

  /**
   *
   * @param stream  : DataStream
   * @param toSQLFn : 转换成SQL的函数,有用户提供.
   * @tparam T : DataStream里的流的数据类型
   * @return
   */
  def sink[T](stream: DataStream[T])(implicit toSQLFn: T => String): DataStreamSink[T] = {
    val prop = ConfigUtils.getMySQLConf(ctx.paramMap)(instance)
    overwriteParams.foreach(x => prop.put(x._1, x._2))
    val sinkFun = new JdbcSinkFunction[T](prop, toSQLFn)
    val sink = stream.addSink(sinkFun)
    afterSink(sink, parallelism, name, uid)
  }

}

class JdbcSinkFunction[T](config: Properties, toSQLFn: T => String) extends RichSinkFunction[T] with Logger {
  private var connection: Connection = _
  private var statement: Statement = _
  private val batchSize = config.getOrElse(KEY_JDBC_INSERT_BATCH, s"${DEFAULT_JDBC_INSERT_BATCH}").toInt
  val offset: AtomicLong = new AtomicLong(0L)
  val timer: Timer = new Timer()

  @throws[Exception]
  override def open(parameters: Configuration): Unit = {
    logInfo("[StreamX] JdbcSink Open....")
    connection = MySQLUtils.getConnection(config)
    connection.setAutoCommit(false)
  }

  override def invoke(value: T, context: SinkFunction.Context[_]): Unit = {
    require(connection != null)
    val sql = toSQLFn(value)
    batchSize match {
      case 1 =>
        try {
          statement = connection.prepareStatement(sql)
          statement.asInstanceOf[PreparedStatement].executeUpdate
          connection.commit()
        } catch {
          case e: Exception =>
            logError(s"[StreamX] JdbcSink invoke error:${sql}")
            throw e
          case _ =>
        }
      case batch =>
        try {
          statement = connection.createStatement()
          statement.addBatch(sql)
          offset.incrementAndGet() % batch match {
            case 0 => execBatch()
            case _ =>
          }
          timer.schedule(new TimerTask() {
            override def run(): Unit = execBatch()
          }, 1000)
        } catch {
          case e: Exception =>
            logError(s"[StreamX] JdbcSink batch invoke error:${sql}")
            throw e
          case _ =>
        }
    }

  }

  override def close(): Unit = MySQLUtils.close(statement, connection)

  private[this] def execBatch(): Unit = {
    if (offset.get() > 0) {
      val count = statement.executeBatch().sum
      statement.clearBatch()
      connection.commit()
      offset.set(0L)
      logInfo(s"[StreamX] JdbcSink batch $count successful..")
    }
  }


}


class JdbcOutputFormat[T: TypeInformation](implicit prop: Properties, toSQlFun: T => String) extends RichOutputFormat[T] with Logger {

  val sinkFunction = new JdbcSinkFunction[T](prop, toSQlFun)

  var configuration: Configuration = _

  override def configure(configuration: Configuration): Unit = this.configuration = configuration

  override def open(taskNumber: Int, numTasks: Int): Unit = sinkFunction.open(this.configuration)

  override def writeRecord(record: T): Unit = sinkFunction.invoke(record, null)

  override def close(): Unit = sinkFunction.close()
}




