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
package org.apache.spark.sql.execution

import org.apache.gluten.backendsapi.BackendsApiManager
import org.apache.gluten.config.ReservedKeys
import org.apache.gluten.execution.ValidatablePlan
import org.apache.gluten.execution.ValidationResult
import org.apache.gluten.extension.columnar.transition.Convention
import org.apache.gluten.sql.shims.SparkShimLoader

import org.apache.spark._
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.Serializer
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.Statistics
import org.apache.spark.sql.catalyst.plans.physical._
import org.apache.spark.sql.execution.CoalesceExec.EmptyPartition
import org.apache.spark.sql.execution.exchange._
import org.apache.spark.sql.execution.metric.SQLShuffleWriteMetricsReporter
import org.apache.spark.sql.metric.SQLColumnarShuffleReadMetricsReporter
import org.apache.spark.sql.vectorized.ColumnarBatch

import scala.concurrent.Future

case class ColumnarShuffleExchangeExec(
    override val outputPartitioning: Partitioning,
    child: SparkPlan,
    shuffleOrigin: ShuffleOrigin = ENSURE_REQUIREMENTS,
    projectOutputAttributes: Seq[Attribute],
    advisoryPartitionSize: Option[Long] = None)
  extends ShuffleExchangeLike
  with ValidatablePlan {
  private[sql] lazy val writeMetrics =
    SQLShuffleWriteMetricsReporter.createShuffleWriteMetrics(sparkContext)

  private[sql] lazy val readMetrics =
    SQLColumnarShuffleReadMetricsReporter.createShuffleReadMetrics(sparkContext)

  val useSortBasedShuffle: Boolean =
    BackendsApiManager.getSparkPlanExecApiInstance.useSortBasedShuffle(outputPartitioning, output)

  // Note: "metrics" is made transient to avoid sending driver-side metrics to tasks.
  @transient override lazy val metrics =
    BackendsApiManager.getMetricsApiInstance
      .genColumnarShuffleExchangeMetrics(
        sparkContext,
        useSortBasedShuffle) ++ readMetrics ++ writeMetrics

  @transient lazy val inputColumnarRDD: RDD[ColumnarBatch] = child.executeColumnar()

  // 'mapOutputStatisticsFuture' is only needed when enable AQE.
  @transient override lazy val mapOutputStatisticsFuture: Future[MapOutputStatistics] = {
    if (inputColumnarRDD.getNumPartitions == 0) {
      Future.successful(null)
    } else {
      sparkContext.submitMapStage(columnarShuffleDependency)
    }
  }

  /**
   * A [[ShuffleDependency]] that will partition rows of its child based on the partitioning scheme
   * defined in `newPartitioning`. Those partitions of the returned ShuffleDependency will be the
   * input of shuffle.
   */
  @transient
  lazy val columnarShuffleDependency: ShuffleDependency[Int, ColumnarBatch, ColumnarBatch] = {
    BackendsApiManager.getSparkPlanExecApiInstance.genShuffleDependency(
      inputColumnarRDD,
      child.output,
      projectOutputAttributes,
      outputPartitioning,
      serializer,
      writeMetrics,
      metrics,
      useSortBasedShuffle)
  }

  // super.stringArgs ++ Iterator(output.map(o => s"${o}#${o.dataType.simpleString}"))
  val serializer: Serializer = BackendsApiManager.getSparkPlanExecApiInstance
    .createColumnarBatchSerializer(schema, metrics, useSortBasedShuffle)

  var cachedShuffleRDD: ShuffledColumnarBatchRDD = _

  override protected def doValidateInternal(): ValidationResult = {
    BackendsApiManager.getValidatorApiInstance
      .doColumnarShuffleExchangeExecValidate(output, outputPartitioning, child)
      .map {
        reason =>
          ValidationResult.failed(
            s"Found schema check failure for schema ${child.schema} due to: $reason")
      }
      .getOrElse(ValidationResult.succeeded)
  }

  override def nodeName: String = "ColumnarExchange"

  override def numMappers: Int = inputColumnarRDD.getNumPartitions

  override def numPartitions: Int = columnarShuffleDependency.partitioner.numPartitions

  override def runtimeStatistics: Statistics = {
    val dataSize = metrics("dataSize").value
    val rowCount = metrics(SQLShuffleWriteMetricsReporter.SHUFFLE_RECORDS_WRITTEN).value
    Statistics(dataSize, Some(rowCount))
  }

  override def getShuffleRDD(partitionSpecs: Array[ShufflePartitionSpec]): RDD[ColumnarBatch] = {
    new ShuffledColumnarBatchRDD(columnarShuffleDependency, readMetrics, partitionSpecs)
  }

  override def stringArgs: Iterator[Any] = {
    val shuffleWriterType = {
      if (useSortBasedShuffle) ReservedKeys.GLUTEN_SORT_SHUFFLE_WRITER
      else ReservedKeys.GLUTEN_HASH_SHUFFLE_WRITER
    }
    super.stringArgs ++ Iterator(s"[shuffle_writer_type=$shuffleWriterType]")
  }

  override def batchType(): Convention.BatchType = BackendsApiManager.getSettings.primaryBatchType

  override def rowType0(): Convention.RowType = Convention.RowType.None

  override def doExecute(): RDD[InternalRow] = {
    throw new UnsupportedOperationException()
  }

  override def doExecuteColumnar(): RDD[ColumnarBatch] = {
    if (cachedShuffleRDD == null) {
      cachedShuffleRDD = new ShuffledColumnarBatchRDD(columnarShuffleDependency, readMetrics)
    }
    cachedShuffleRDD
  }

  override def verboseString(maxFields: Int): String = toString(super.verboseString(maxFields))

  override def simpleString(maxFields: Int): String = toString(super.simpleString(maxFields))

  private def toString(original: String): String = {
    original + ", [OUTPUT] " + output.map(attr => attr.name + ":" + attr.dataType).toString()
  }

  override def output: Seq[Attribute] = if (projectOutputAttributes != null) {
    projectOutputAttributes
  } else {
    child.output
  }

  protected def withNewChildInternal(newChild: SparkPlan): ColumnarShuffleExchangeExec =
    copy(child = newChild)
}

object ColumnarShuffleExchangeExec extends Logging {

  def apply(
      plan: ShuffleExchangeExec,
      child: SparkPlan,
      shuffleOutputAttributes: Seq[Attribute]): ColumnarShuffleExchangeExec = {
    ColumnarShuffleExchangeExec(
      plan.outputPartitioning,
      child,
      plan.shuffleOrigin,
      shuffleOutputAttributes,
      advisoryPartitionSize = SparkShimLoader.getSparkShims.getShuffleAdvisoryPartitionSize(plan)
    )
  }

  class DummyPairRDDWithPartitions(@transient private val sc: SparkContext, numPartitions: Int)
    extends RDD[Product2[Int, InternalRow]](sc, Nil) {

    override def getPartitions: Array[Partition] =
      Array.tabulate(numPartitions)(i => EmptyPartition(i))

    override def compute(
        split: Partition,
        context: TaskContext): Iterator[Product2[Int, InternalRow]] = {
      throw new UnsupportedOperationException
    }
  }
}
