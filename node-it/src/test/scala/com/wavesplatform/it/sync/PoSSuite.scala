package com.wavesplatform.it.sync

import com.typesafe.config.Config
import com.wavesplatform.account.{KeyPair, PrivateKey, PublicKey}
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.{Base58, EitherExt2}
import com.wavesplatform.consensus.FairPoSCalculator
import com.wavesplatform.consensus.nxt.NxtLikeConsensusBlockData
import com.wavesplatform.crypto
import com.wavesplatform.http.DebugMessage
import com.wavesplatform.it.api.AsyncNetworkApi.NodeAsyncNetworkApi
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.transactions.NodesFromDocker
import com.wavesplatform.it.{NodeConfigs, WaitForHeight2}
import com.wavesplatform.network.RawBytes
import org.scalatest.{CancelAfterFailure, FunSuite, Matchers}
import play.api.libs.json.{JsSuccess, Json, Reads}

import scala.util.Random

class PoSSuite extends FunSuite with Matchers with NodesFromDocker with WaitForHeight2 with CancelAfterFailure {

  private val signerPK = KeyPair.fromSeed(nodeConfigs.last.getString("account-seed")).explicitGet()

  private implicit val nxtCDataReads: Reads[NxtLikeConsensusBlockData] = Reads { json =>
    val bt = (json \ "base-target").as[Long]
    val gs = (json \ "generation-signature").as[String]

    JsSuccess(NxtLikeConsensusBlockData(bt, ByteStr.decodeBase58(gs).get))
  }

  test("Node mines several blocks, integration test checks that block timestamps equal to time of appearence (+-1100ms)") {

    val height = nodes.last.height

    for (h <- height to (height + 10)) {

      val block = forgeBlock(h, signerPK)()

      nodes.waitForHeightArise()

      val newTimestamp = blockTimestamp(h + 1)

      block.header.timestamp shouldBe (newTimestamp +- 1100)
    }
  }

  test("Accept correct block") {

    nodes.last.close()
    val height = nodes.head.height
    val block  = forgeBlock(height, signerPK)()

    waitForBlockTime(block)

    nodes.head.printDebugMessage(DebugMessage(s"Send block for $height"))
    nodes.head.sendByNetwork(RawBytes.fromBlock(block))

    nodes.head.waitForHeight(height + 1)

    val newBlockSig = blockSignature(height + 1)

    newBlockSig sameElements block.uniqueId.arr
  }

  test("Reject block with invalid delay") {
    val height = nodes.head.height
    val block  = forgeBlock(height, signerPK)(updateDelay = _ - 1000)

    waitForBlockTime(block)

    nodes.head.sendByNetwork(RawBytes.fromBlock(block))
    nodes.head.waitForHeight(height + 1)

    val newBlockSig = blockSignature(height + 1)

    newBlockSig should not be block.uniqueId.arr
  }

  test("Reject block with invalid BT") {
    val height = nodes.head.height
    val block  = forgeBlock(height, signerPK)(updateBaseTarget = _ + 2)

    waitForBlockTime(block)

    nodes.head.sendByNetwork(RawBytes.fromBlock(block))

    nodes.head.waitForHeight(height + 1)

    val newBlockSig = blockSignature(height + 1)

    newBlockSig should not be block.uniqueId.arr
  }

  test("Reject block with invalid generation signature") {
    val height = nodes.head.height
    val block = forgeBlock(height, signerPK)(updateGenSig = (gs: ByteStr) => {
      val arr  = gs.arr
      val init = arr.init
      Random.nextBytes(arr)
      ByteStr(init :+ arr.last)
    })

    waitForBlockTime(block)

    nodes.head.printDebugMessage(DebugMessage(s"Send invalid block for $height"))
    nodes.head.sendByNetwork(RawBytes.fromBlock(block))

    nodes.head.waitForHeight(height + 1)

    val newBlockSig = blockSignature(height + 1)

    newBlockSig should not be block.uniqueId.arr
  }

  test("Reject block with invalid signature") {
    val otherNodePK = KeyPair.fromSeed(nodeConfigs.head.getString("account-seed")).explicitGet()

    val height = nodes.head.height
    val block  = forgeBlock(height, signerPK)(updateBaseTarget = _ + 2)

    val signature = ByteStr(crypto.sign(otherNodePK, block.bytes()))
    val resignedBlock =
      block
        .copy(signature = signature)

    waitForBlockTime(resignedBlock)

    nodes.head.sendByNetwork(RawBytes.fromBlock(resignedBlock))

    nodes.head.waitForHeight(height + 1)

    val newBlockSig = blockSignature(height + 1)

    newBlockSig should not be resignedBlock.uniqueId.arr
  }

  def waitForBlockTime(block: Block): Unit = {
    val timeout = block.header.timestamp - System.currentTimeMillis()

    if (timeout > 0) Thread.sleep(timeout)
  }

  def blockInfo(height: Int): (Array[Byte], Long, NxtLikeConsensusBlockData) = {
    val lastBlock      = Json.parse(nodes.head.get(s"/blocks/at/$height").getResponseBody)
    val lastBlockId    = Base58.tryDecodeWithLimit((lastBlock \ "signature").as[String]).get
    val lastBlockTS    = (lastBlock \ "timestamp").as[Long]
    val lastBlockCData = (lastBlock \ "nxt-consensus").as[NxtLikeConsensusBlockData]

    (lastBlockId, lastBlockTS, lastBlockCData)
  }

  def blockTimestamp(h: Int): Long = {
    (Json.parse(
      nodes.head
        .get(s"/blocks/at/$h")
        .getResponseBody
    ) \ "timestamp").as[Long]
  }

  def blockSignature(h: Int): Array[Byte] = {
    Base58
      .tryDecodeWithLimit(
        (Json.parse(
          nodes.head
            .get(s"/blocks/at/$h")
            .getResponseBody
        ) \ "signature").as[String]
      )
      .get
  }

  private val vrfActivationHeight = 100

  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(_.quorum(3))
      .overrideBase(
        _.raw(
          s"""
          |waves {
          |  blockchain {
          |    custom {
          |      functionality {
          |        pre-activated-features = {
          |          2 = 0
          |          3 = 0
          |          4 = 0
          |          5 = 0
          |          6 = 0
          |          7 = 0
          |          8 = 0
          |          15 = $vrfActivationHeight
          |        }
          |      }
          |    }
          |  }
          |}
        """.stripMargin
        )
      )
      .overrideBase(_.nonMiner)
      .withDefault(3)
      .withSpecial(_.raw("waves.miner.enable = yes"))
      .buildNonConflicting()

  private def generatorSignature(signature: Array[Byte], publicKey: PublicKey): Array[Byte] = {
    val s = new Array[Byte](crypto.DigestLength * 2)
    System.arraycopy(signature, 0, s, 0, crypto.DigestLength)
    System.arraycopy(publicKey.arr, 0, s, crypto.DigestLength, crypto.DigestLength)
    crypto.fastHash(s)
  }

  private def generationVrfSignature(hitSource: ByteStr, privateKey: PrivateKey): Array[Byte] = {
    crypto.signVRF(privateKey, hitSource.arr).arr
  }

  private val hitSources = collection.mutable.Map[Int, ByteStr]()
  private def hitSourceForHeight(height: Int, publicKey: PublicKey): ByteStr = {
    val hitHeight = if (height > 100) height - 100 else height
    hitSources.getOrElseUpdate(
      height, {
        val genSigAtHitHeight = blockInfo(hitHeight)._3.generationSignature
        if (hitHeight == 1 || hitHeight < vrfActivationHeight) genSigAtHitHeight
        else crypto.verifyVRF(genSigAtHitHeight, hitSourceForHeight(hitHeight - 1, publicKey), publicKey).explicitGet()
      }
    )
  }

  def forgeBlock(
      height: Int,
      signerPK: KeyPair
  )(updateDelay: Long => Long = identity, updateBaseTarget: Long => Long = identity, updateGenSig: ByteStr => ByteStr = identity): Block = {

    val ggParentTS =
      if (height >= 3)
        Some(
          (Json
            .parse(nodes.head.get(s"/blocks/at/${height - 2}").getResponseBody) \ "timestamp").as[Long]
        )
      else None

    val (lastBlockId, lastBlockTS, lastBlockCData) = blockInfo(height)

    val hitSource = hitSourceForHeight(height, signerPK.publicKey).arr

    val genSig: ByteStr =
      if (height < vrfActivationHeight)
        updateGenSig(
          ByteStr(generatorSignature(hitSource, signerPK))
        )
      else
        updateGenSig(
          ByteStr(generationVrfSignature(hitSource, signerPK.privateKey))
        )

    val validBlockDelay: Long = updateDelay(
      FairPoSCalculator
        .calculateDelay(
          hit(genSig),
          lastBlockCData.baseTarget,
          nodes.head.accountBalances(signerPK.stringRepr)._2
        )
    )

    val bastTarget: Long = updateBaseTarget(
      FairPoSCalculator
        .calculateBaseTarget(
          10,
          height,
          lastBlockCData.baseTarget,
          lastBlockTS,
          ggParentTS,
          lastBlockTS + validBlockDelay
        )
    )

    Block
      .buildAndSign(
        version = 3: Byte,
        timestamp = lastBlockTS + validBlockDelay,
        reference = ByteStr(lastBlockId),
        baseTarget = bastTarget,
        generationSignature = genSig,
        txs = Nil,
        signer = signerPK,
        featureVotes = Seq.empty,
        rewardVote = -1L
      )
      .explicitGet()
  }

  private def hit(generatorSignature: Array[Byte]): BigInt = BigInt(1, generatorSignature.take(8).reverse)
}
