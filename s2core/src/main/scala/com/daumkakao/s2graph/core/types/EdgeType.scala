package com.daumkakao.s2graph.core.types

import com.daumkakao.s2graph.core.GraphUtil
import com.daumkakao.s2graph.core.models.{LabelIndex, LabelMeta}
import org.apache.hadoop.hbase.util.Bytes
import LabelWithDirection._
import play.api.Logger

/**
 * Created by shon on 5/29/15.
 */
object EdgeType {
  def propsToBytes(props: Seq[(Byte, InnerVal)]): Array[Byte] = {
    val len = props.length
    assert(len < Byte.MaxValue)
    var bytes = Array.fill(1)(len.toByte)
    for ((k, v) <- props) bytes = Bytes.add(bytes, v.bytes)

    //        Logger.debug(s"propsToBytes: $props => ${bytes.toList}")
    bytes
  }

  def propsToKeyValues(props: Seq[(Byte, InnerVal)]): Array[Byte] = {
    val len = props.length
    assert(len < Byte.MaxValue)
    var bytes = Array.fill(1)(len.toByte)
    for ((k, v) <- props) bytes = Bytes.add(bytes, Array.fill(1)(k), v.bytes)
    //        Logger.error(s"propsToBytes: $props => ${bytes.toList}")
    bytes
  }

  def propsToKeyValuesWithTs(props: Seq[(Byte, InnerValWithTs)]): Array[Byte] = {
    val len = props.length
    assert(len < Byte.MaxValue)
    var bytes = Array.fill(1)(len.toByte)
    for ((k, v) <- props) bytes = Bytes.add(bytes, Array.fill(1)(k), v.bytes)
    //        Logger.error(s"propsToBytes: $props => ${bytes.toList}")
    bytes
  }

  def bytesToKeyValues(bytes: Array[Byte], offset: Int, version: String = "v2"): (Seq[(Byte, InnerVal)], Int) = {
    var pos = offset
    val len = bytes(pos)
    pos += 1
    val kvs = for (i <- (0 until len)) yield {
      val k = bytes(pos)
      pos += 1
      val v =
        if (version == "v1") InnerValV1(bytes, pos)
        else InnerVal(bytes, pos)
      pos += v.bytes.length
      (k -> v)
    }
    val ret = (kvs.toList, pos)
    //    Logger.debug(s"bytesToProps: $ret")
    ret
  }

  def bytesToKeyValuesWithTs(bytes: Array[Byte], offset: Int, version: String = "v2"): (Seq[(Byte, InnerValWithTs)], Int) = {
    var pos = offset
    val len = bytes(pos)
    pos += 1
    val kvs = for (i <- (0 until len)) yield {
      val k = bytes(pos)
      pos += 1
      val v =
        if (version == "v1") InnerValWithTsV1(bytes, pos)
        else InnerValWithTs(bytes, pos)
      pos += v.bytes.length
      (k -> v)
    }
    val ret = (kvs.toList, pos)
    //    Logger.debug(s"bytesToProps: $ret")
    ret
  }

  def bytesToProps(bytes: Array[Byte], offset: Int, version: String = "v2"): (Seq[(Byte, InnerVal)], Int) = {
    var pos = offset
    val len = bytes(pos)
    pos += 1
    val kvs = for (i <- (0 until len)) yield {
      val k = LabelMeta.emptyValue
      val v =
        if (version == "v1") InnerValV1(bytes, pos)
        else InnerVal(bytes, pos)

      pos += v.bytes.length
      (k -> v)
    }
    //    Logger.error(s"bytesToProps: $kvs")
    val ret = (kvs.toList, pos)

    ret
  }

  object EdgeRowKey {
    val propMode = 0
    val isEdge = true

    def apply(bytes: Array[Byte], offset: Int, version: String = "v2"): EdgeRowKey = {
      var pos = offset
      val copmositeId =
        if (version == "v1") CompositeIdV1(bytes, pos, isEdge, true)
        else CompositeId(bytes, pos, isEdge, true)

      pos += copmositeId.bytesInUse
      val labelWithDir = LabelWithDirection(Bytes.toInt(bytes, pos, 4))
      pos += 4
      val (labelOrderSeq, isInverted) = bytesToLabelIndexSeqWithIsInverted(bytes, pos)
      EdgeRowKey(copmositeId, labelWithDir, labelOrderSeq, isInverted)
    }

  }

  //TODO: split inverted table? cf?
  case class EdgeRowKey(srcVertexId: CompositeId,
                        labelWithDir: LabelWithDirection,
                        labelOrderSeq: Byte,
                        isInverted: Boolean) extends HBaseType {
    //    play.api.Logger.debug(s"$this")
    lazy val innerSrcVertexId = srcVertexId.updateUseHash(true)
    lazy val bytes = Bytes.add(innerSrcVertexId.bytes, labelWithDir.bytes, labelOrderSeqWithIsInverted(labelOrderSeq, isInverted))
  }

  object EdgeQualifier {
    val isEdge = true
    val degreeTgtId = Byte.MinValue
    val degreeOp = 0.toByte
    val defaultTgtId = null

    def apply(bytes: Array[Byte], offset: Int, len: Int, version: String = "v2"): EdgeQualifier = {
      var pos = offset
      //      val op = bytes(offset + len - 1)
      val op = GraphUtil.defaultOpByte
      val (props, tgtVertexId) = {
        val (props, endAt) = bytesToProps(bytes, pos, version)
        //        val tgtVertexId = CompositeId(bytes, endAt, true, false)
        /** check if target vertex Id is included indexProps or seperate field */
        val tgtVertexId = if (endAt == offset + len) {
          defaultTgtId
        } else {
          if (version == "v1") CompositeIdV1(bytes, endAt, true, false)
          else CompositeId(bytes, endAt, true, false)
        }
        (props, tgtVertexId)
      }
      EdgeQualifier(props, tgtVertexId, op)
    }
  }

  case class EdgeQualifier(props: Seq[(Byte, InnerVal)], tgtVertexId: CompositeId, op: Byte) extends HBaseType {

    lazy val opBytes = Array.fill(1)(op)
    lazy val innerTgtVertexId = tgtVertexId.updateUseHash(false)
    lazy val propsMap = props.toMap
    lazy val propsBytes = propsToBytes(props)
    lazy val bytes = {
      /** check if target vertex id is already included in indexProps. */
      propsMap.get(LabelMeta.toSeq) match {
        case None => Bytes.add(propsBytes, innerTgtVertexId.bytes)
        case Some(vId) => propsBytes
      }
      //      Bytes.add(propsBytes, innerTgtVertexId.bytes, opBytes)
    }

    //TODO:
    def propsKVs(labelId: Int, labelOrderSeq: Byte, version: String = "v2"): List[(Byte, InnerVal)] = {
      val filtered = props.filter(kv => kv._1 != LabelMeta.emptyValue)
      if (filtered.isEmpty) {
        val indexOpt = if (version == "v1") {
          com.daumkakao.s2graph.core.mysqls.LabelIndex.findByLabeIdAndSeq(labelId, labelOrderSeq)
        } else {
          LabelIndex.findByLabelIdAndSeq(labelId, labelOrderSeq)
        }
        val opt = for (index <- LabelIndex.findByLabelIdAndSeq(labelId, labelOrderSeq)) yield {
          val v = index.metaSeqs.zip(props.map(_._2))
          v
        }
        opt.getOrElse(List.empty[(Byte, InnerVal)])
      } else {
        filtered.toList
      }
    }

    override def equals(obj: Any) = {
      obj match {
        case other: EdgeQualifier =>
          props.map(_._2) == other.props.map(_._2) &&
            tgtVertexId == other.tgtVertexId &&
            op == other.op
        case _ => false
      }
    }
  }

  object EdgeQualifierInverted {
    def apply(bytes: Array[Byte], offset: Int, version: String = "v2"): EdgeQualifierInverted = {
      val tgtVertexId =
        if (version == "v1") CompositeIdV1(bytes, offset, true, false)
        else CompositeId(bytes, offset, true, false)
      EdgeQualifierInverted(tgtVertexId)
    }
  }

  case class EdgeQualifierInverted(tgtVertexId: CompositeId) extends HBaseType {
    //    play.api.Logger.debug(s"$this")
    val innerTgtVertexId = tgtVertexId.updateUseHash(false)
    lazy val bytes = innerTgtVertexId.bytes
  }

  object EdgeValue {
    def apply(bytes: Array[Byte], offset: Int, version: String = "v2"): EdgeValue = {
      val (props, endAt) = bytesToKeyValues(bytes, offset, version)
      EdgeValue(props)
    }
  }

  case class EdgeValue(props: Seq[(Byte, InnerVal)]) extends HBaseType {
    lazy val bytes = propsToKeyValues(props)
  }

  object EdgeValueInverted {
    def apply(bytes: Array[Byte], offset: Int, version: String = "v2"): EdgeValueInverted = {
      var pos = offset
      val op = bytes(pos)
      pos += 1
      val (props, endAt) = bytesToKeyValuesWithTs(bytes, pos, version)
      EdgeValueInverted(op, props)
    }
  }

  case class EdgeValueInverted(op: Byte, props: Seq[(Byte, InnerValWithTs)]) extends HBaseType {
    lazy val bytes = Bytes.add(Array.fill(1)(op), propsToKeyValuesWithTs(props))
  }

}
