/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.apiscope.services

import javax.inject.{Inject, Singleton}
import play.api.Logger.logger
import play.api.libs.json.{JsError, JsSuccess, Json}
import uk.gov.hmrc.apiscope.models.Scope
import uk.gov.hmrc.apiscope.repository.ScopeRepository

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

@Singleton
class ScopeJsonFileService @Inject()(scopeRepository: ScopeRepository,
                                     fileReader: ScopeJsonFileReader)(implicit val ec: ExecutionContext) {

  private def saveScopes(scopes: Seq[Scope]): Future[Seq[Scope]] =
    Future.sequence(scopes.map(scopeRepository.save))

  private def fetchScopes(): Future[Seq[Scope]] =
    scopeRepository.fetchAll()

  private def logScopes(): Unit = {
    scopeRepository.fetchAll() onComplete{
      case Success(seqScopes) => {
        val perLogScopes = 30
        val groupedSeq = seqScopes.grouped(perLogScopes)
        logger.info("Fetching scopes during api-scopes application startup.")
        groupedSeq.foreach(group => logger.info(Json.toJson(group).toString()))
      }
      case Failure(err) => logger.info(s"Fetching Scopes from api-scope repo failed with error $err..")
    }
  }

  try {
    fileReader.readFile.map(s => Json.parse(s).validate[Seq[Scope]] match {
      case JsSuccess(scopes: Seq[Scope], _) =>
        logger.info(s"Inserting ${scopes.size} Scopes from bundled file")
        saveScopes(scopes)
      case JsError(errors) => logger.error("Unable to parse JSON into Scopes", errors.mkString("; "))
    })
    logScopes()
  } catch {
    case _: java.nio.file.NoSuchFileException => logger.info("No Scopes file found to process")
    case NonFatal(e) =>
      logger.error("Scopes file does not contain valid JSON", e)
  }

  try {
    fileReader.readDryRunFile.map(s => Json.parse(s).validate[Seq[Scope]] match {
      case JsSuccess(scopes: Seq[Scope], _) => {
        logger.info(s"Fetching ${scopes.size} Scopes from scopes dry run file")
        fetchScopes() map( repoScopes =>
        logger.info(reconcileScopesInDryRun(scopes, repoScopes)))
      }
      case JsError(errors) => logger.error("Unable to parse dry run JSON file", errors.mkString("; "))
    })
  } catch {
    case _: java.nio.file.NoSuchFileException => logger.info("No Scopes file found to process")
    case NonFatal(e) =>
      logger.error("Scopes file does not contain valid JSON", e)
  }

  def reconcileScopesInDryRun(fileScopes: Seq[Scope], repoScopes: Seq[Scope]) : String = {
    val toSet1 = repoScopes.toSet
    val diff1 = fileScopes.filterNot(toSet1)
    val toSet2 = fileScopes.toSet
    val diff2 = repoScopes.filterNot(toSet2)

    if(diff1.isEmpty && diff2.isEmpty) {
      "Scopes in file & repo exactly match."
    } else if (!diff1.isEmpty) {
      s"In JSON file ${diff1.size} scope(s) does not match REPO Scopes. Example: ${diff1.head}"
    }
    else {
      s"In REPO ${diff2.size} scope(s) does not match JSON Scopes. Example: ${diff2.head}"
    }
  }
}
