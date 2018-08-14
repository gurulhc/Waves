package com.wavesplatform.transaction.smart

import cats.syntax.all._
import com.wavesplatform.crypto
import com.wavesplatform.metrics.{Instrumented, TxMetrics}
import com.wavesplatform.state._
import com.wavesplatform.transaction.ValidationError.{GenericError, ScriptExecutionError, TransactionNotAllowedByScript}
import com.wavesplatform.transaction._
import com.wavesplatform.transaction.assets._
import com.wavesplatform.transaction.smart.script.{Script, ScriptRunner}
import com.wavesplatform.transaction.transfer._
import com.wavesplatform.utils.ScorexLogging
import kamon.Kamon
import shapeless.Coproduct

object Verifier extends Instrumented with ScorexLogging {

  private val accountScriptStats = Kamon.metrics.entity(TxMetrics, "account-script-execution-stats")
  private val assetScriptStats   = Kamon.metrics.entity(TxMetrics, "asset-script-execution-stats")

  private val signatureStats = Kamon.metrics.entity(TxMetrics, "signature-verification-stats")

  def apply(blockchain: Blockchain, currentBlockHeight: Int)(tx: Transaction): Either[ValidationError, Transaction] =
    (tx match {
      case _: GenesisTransaction => Right(tx)
      case pt: ProvenTransaction =>
        (pt, blockchain.accountScript(pt.sender)) match {
          case (_, Some(script)) => verify(blockchain, script, currentBlockHeight, pt, false)
          case (stx: SignedTransaction, None) =>
            measureAndIncSuccessful(signatureStats.processingTime(stx.builder.typeId), signatureStats.processed(stx.builder.typeId)) {
              stx.signaturesValid()
            }
          case _ => verifyAsEllipticCurveSignature(pt)
        }
    }).flatMap(tx => {
      for {
        assetId <- tx match {
          case t: TransferTransaction     => t.assetId
          case t: MassTransferTransaction => t.assetId
          case t: BurnTransaction         => Some(t.assetId)
          case t: ReissueTransaction      => Some(t.assetId)
          case _                          => None
        }

        script <- blockchain.assetDescription(assetId).flatMap(_.script)
      } yield verify(blockchain, script, currentBlockHeight, tx, true)
    }.getOrElse(Either.right(tx)))

  def verify(blockchain: Blockchain,
             script: Script,
             height: Int,
             transaction: Transaction,
             isTokenScript: Boolean): Either[ValidationError, Transaction] = {
    val stats =
      if (isTokenScript) assetScriptStats
      else accountScriptStats

    val txTypeId = transaction.builder.typeId

    measureAndIncSuccessful(stats.processingTime(txTypeId), stats.processed(txTypeId)) {
      ScriptRunner[Boolean](height, Coproduct(transaction), blockchain, script) match {
        case (ctx, Left(execError)) => Left(ScriptExecutionError(script.text, execError, ctx.letDefs, isTokenScript))
        case (ctx, Right(false)) =>
          Left(TransactionNotAllowedByScript(ctx.letDefs, script.text, isTokenScript))
        case (_, Right(true)) => Right(transaction)
      }
    }
  }

  def verifyAsEllipticCurveSignature(pt: ProvenTransaction): Either[ValidationError, ProvenTransaction] = {
    val txTypeId = pt.builder.typeId
    measureAndIncSuccessful(signatureStats.processingTime(txTypeId), signatureStats.processed(txTypeId)) {
      pt.proofs.proofs match {
        case p :: Nil =>
          Either.cond(crypto.verify(p.arr, pt.bodyBytes(), pt.sender.publicKey),
                      pt,
                      GenericError(s"Script doesn't exist and proof doesn't validate as signature for $pt"))
        case _ => Left(GenericError("Transactions from non-scripted accounts must have exactly 1 proof"))
      }
    }
  }

}
