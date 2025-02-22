/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.engine.spark.operation

import java.util.concurrent.RejectedExecutionException

import scala.collection.JavaConverters._

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.kyuubi.SparkDatasetHelper
import org.apache.spark.sql.types._

import org.apache.kyuubi.{KyuubiSQLException, Logging}
import org.apache.kyuubi.config.KyuubiConf.OPERATION_RESULT_MAX_ROWS
import org.apache.kyuubi.engine.spark.KyuubiSparkUtil._
import org.apache.kyuubi.operation.{ArrayFetchIterator, IterableFetchIterator, OperationState}
import org.apache.kyuubi.operation.log.OperationLog
import org.apache.kyuubi.session.Session

class ExecuteStatement(
    session: Session,
    override val statement: String,
    override val shouldRunAsync: Boolean,
    queryTimeout: Long,
    incrementalCollect: Boolean)
  extends SparkOperation(session) with Logging {

  private val operationLog: OperationLog = OperationLog.createOperationLog(session, getHandle)
  override def getOperationLog: Option[OperationLog] = Option(operationLog)
  override protected def supportProgress: Boolean = true

  override protected def resultSchema: StructType = {
    if (result == null || result.schema.isEmpty) {
      new StructType().add("Result", "string")
    } else {
      result.schema
    }
  }

  override protected def beforeRun(): Unit = {
    OperationLog.setCurrentOperationLog(operationLog)
    setState(OperationState.PENDING)
    setHasResultSet(true)
  }

  override protected def afterRun(): Unit = {
    OperationLog.removeCurrentOperationLog()
  }

  private def executeStatement(): Unit = withLocalProperties {
    try {
      setState(OperationState.RUNNING)
      info(diagnostics)
      Thread.currentThread().setContextClassLoader(spark.sharedState.jarClassLoader)
      addOperationListener()
      result = spark.sql(statement)

      iter =
        if (incrementalCollect) {
          info("Execute in incremental collect mode")
          if (arrowEnabled) {
            new IterableFetchIterator[Array[Byte]](new Iterable[Array[Byte]] {
              override def iterator: Iterator[Array[Byte]] = SparkDatasetHelper.toArrowBatchRdd(
                convertComplexType(result)).toLocalIterator
            })
          } else {
            new IterableFetchIterator[Row](new Iterable[Row] {
              override def iterator: Iterator[Row] = result.toLocalIterator().asScala
            })
          }
        } else {
          val resultMaxRows = spark.conf.getOption(OPERATION_RESULT_MAX_ROWS.key).map(_.toInt)
            .getOrElse(session.sessionManager.getConf.get(OPERATION_RESULT_MAX_ROWS))
          if (resultMaxRows <= 0) {
            info("Execute in full collect mode")
            if (arrowEnabled) {
              new ArrayFetchIterator(
                SparkDatasetHelper.toArrowBatchRdd(
                  convertComplexType(result)).collect())
            } else {
              new ArrayFetchIterator(result.collect())
            }
          } else {
            info(s"Execute with max result rows[$resultMaxRows]")
            if (arrowEnabled) {
              // this will introduce shuffle and hurt performance
              new ArrayFetchIterator(
                SparkDatasetHelper.toArrowBatchRdd(
                  convertComplexType(result.limit(resultMaxRows))).collect())
            } else {
              new ArrayFetchIterator(result.take(resultMaxRows))
            }
          }
        }
      setCompiledStateIfNeeded()
      setState(OperationState.FINISHED)
    } catch {
      onError(cancel = true)
    } finally {
      shutdownTimeoutMonitor()
    }
  }

  override protected def runInternal(): Unit = {
    addTimeoutMonitor(queryTimeout)
    if (shouldRunAsync) {
      val asyncOperation = new Runnable {
        override def run(): Unit = {
          OperationLog.setCurrentOperationLog(operationLog)
          executeStatement()
        }
      }

      try {
        val sparkSQLSessionManager = session.sessionManager
        val backgroundHandle = sparkSQLSessionManager.submitBackgroundOperation(asyncOperation)
        setBackgroundHandle(backgroundHandle)
      } catch {
        case rejected: RejectedExecutionException =>
          setState(OperationState.ERROR)
          val ke =
            KyuubiSQLException("Error submitting query in background, query rejected", rejected)
          setOperationException(ke)
          throw ke
      }
    } else {
      executeStatement()
    }
  }

  def setCompiledStateIfNeeded(): Unit = synchronized {
    if (getStatus.state == OperationState.RUNNING) {
      val lastAccessCompiledTime =
        if (result != null) {
          val phase = result.queryExecution.tracker.phases
          if (phase.contains("parsing") && phase.contains("planning")) {
            val compiledTime = phase("planning").endTimeMs - phase("parsing").startTimeMs
            lastAccessTime + compiledTime
          } else {
            0L
          }
        } else {
          0L
        }
      setState(OperationState.COMPILED)
      if (lastAccessCompiledTime > 0L) {
        lastAccessTime = lastAccessCompiledTime
      }
    }
  }

  // TODO:(fchen) make this configurable
  val kyuubiBeelineConvertToString = true

  def convertComplexType(df: DataFrame): DataFrame = {
    if (kyuubiBeelineConvertToString) {
      SparkDatasetHelper.convertTopLevelComplexTypeToHiveString(df)
    } else {
      df
    }
  }

  override def getResultSetMetadataHints(): Seq[String] =
    Seq(s"__kyuubi_operation_result_format__=$resultFormat")
}
