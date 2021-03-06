/**
Open Bank Project - Transparency / Social Finance Web Application
Copyright (C) 2011, 2012, TESOBE / Music Pictures Ltd

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com
TESOBE / Music Pictures Ltd
Osloerstrasse 16/17
Berlin 13359, Germany

  This product includes software developed at
  TESOBE (http://www.tesobe.com/)
  by
  Simon Redfern : simon AT tesobe DOT com
  Stefan Bethge : stefan AT tesobe DOT com
  Everett Sochowski : everett AT tesobe DOT com
  Ayoub Benali: ayoub AT tesobe DOT com

 */
package code.api

import code.actors.EnvelopeInserter
import net.liftweb.http._
import net.liftweb.http.rest._
import net.liftweb.json.JsonDSL._
import net.liftweb.json.Printer._
import net.liftweb.json.Extraction._
import net.liftweb.json.JsonAST._
import net.liftweb.common.{Failure,Full,Empty, Box, Loggable}
import net.liftweb.mongodb._
import net.liftweb.json.JsonAST.JString
import com.mongodb.casbah.Imports._
import _root_.java.math.MathContext
import org.bson.types._
import org.joda.time.{ DateTime, DateTimeZone }
import java.util.regex.Pattern
import _root_.net.liftweb.util._
import _root_.net.liftweb.mapper._
import _root_.net.liftweb.util.Helpers._
import _root_.net.liftweb.sitemap._
import _root_.scala.xml._
import _root_.net.liftweb.http.S._
import _root_.net.liftweb.http.RequestVar
import _root_.net.liftweb.util.Helpers._
import net.liftweb.mongodb.{ Skip, Limit }
import _root_.net.liftweb.http.S._
import _root_.net.liftweb.mapper.view._
import com.mongodb._
import code.model.dataAccess.LocalStorage
import code.model.traits.ModeratedTransaction
import code.model.traits.View
import code.model.implementedTraits.View
import code.model.traits.BankAccount
import code.model.implementedTraits.Public
import code.model.traits.Bank
import code.model.traits.User
import java.util.Date
import code.api.OAuthHandshake._
import code.model.traits.ModeratedBankAccount
import code.model.dataAccess.APIMetric
import code.model.traits.AccountOwner
import code.model.dataAccess.OBPEnvelope.{OBPOrder, OBPLimit, OBPOffset, OBPOrdering, OBPFromDate, OBPToDate, OBPQueryParam}
import code.model.dataAccess.OBPUser
import code.model.traits.ModeratedOtherBankAccount
import net.liftweb.json.Extraction

object OBPAPI1_1 extends RestHelper with Loggable {

  implicit def errorToJson(error: ErrorMessage): JValue = Extraction.decompose(error)

  val dateFormat = ModeratedTransaction.dateFormat
  private def getUser(httpCode : Int, tokenID : Box[String]) : Box[User] =
  if(httpCode==200)
  {
    import code.model.Token
    logger.info("OAuth header correct ")
    Token.find(By(Token.key, tokenID.get)) match {
      case Full(token) => {
        logger.info("access token found")
        User.findById(token.userId.get)
      }
      case _ =>{
        logger.warn("no token " + tokenID.get + " found")
        Empty
      }
    }
  }
  else
    Empty

  private def isThereOauthHeader : Boolean = {
    S.request match {
      case Full(a) =>  a.header("Authorization") match {
        case Full(parameters) => parameters.contains("OAuth")
        case _ => false
      }
      case _ => false
    }
  }

  private def logAPICall =
    APIMetric.createRecord.
      url(S.uriAndQueryString.getOrElse("")).
      date((now: TimeSpan)).
      save

  serve("obp" / "v1.1" prefix {

    case Nil JsonGet json => {
      logAPICall

      def gitCommit : String = {
        val commit = tryo{
          val properties = new java.util.Properties()
          properties.load(getClass().getClassLoader().getResourceAsStream("git.properties"))
          properties.getProperty("git.commit.id", "")
        }
        commit getOrElse ""
      }

      val apiDetails = {
        ("api" ->
          ("version" -> "1.1") ~
          ("git_commit" -> gitCommit) ~
          ("hosted_by" ->
            ("organisation" -> "TESOBE") ~
            ("email" -> "contact@tesobe.com") ~
            ("phone" -> "+49 (0)30 8145 3994"))) ~
        ("links" ->
          ("rel" -> "banks") ~
          ("href" -> "/banks") ~
          ("method" -> "GET") ~
          ("title" -> "Returns a list of banks supported on this server"))
      }

      JsonResponse(apiDetails)
    }

    case "banks" :: Nil JsonGet json => {
      logAPICall
      def bankToJson( b : Bank) = {
        ("bank" ->
          ("id" -> b.permalink) ~
          ("short_name" -> b.shortName) ~
          ("full_name" -> b.fullName) ~
          ("logo" -> b.logoURL)
        )
      }

      JsonResponse("banks" -> Bank.all.map(bankToJson _ ))
    }
    
    case "banks" :: bankId :: Nil JsonGet json => {
      logAPICall
      
      def bankToJson( b : Bank) = {
        ("bank" ->
          ("id" -> b.permalink) ~
          ("short_name" -> b.shortName) ~
          ("full_name" -> b.fullName) ~
          ("logo" -> b.logoURL)
        )
      }
      
      for {
        b <- Bank(bankId)
      } yield JsonResponse(bankToJson(b))
    }
  })

  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: Nil JsonGet json => {
      //log the API call
      logAPICall

      import code.api.OAuthHandshake._
      val (httpCode, message, oAuthParameters) = validator("protectedResource", "GET")
      val headers = ("Content-type" -> "application/x-www-form-urlencoded") :: Nil

      def asInt(s: Box[String], default: Int): Int = {
        s match {
          case Full(str) => tryo { str.toInt } getOrElse default
          case _ => default
        }
      }
      val limit = asInt(json.header("obp_limit"), 50)
      val offset = asInt(json.header("obp_offset"), 0)
      /**
       * sortBy is currently disabled as it would open up a security hole:
       *
       * sortBy as currently implemented will take in a parameter that searches on the mongo field names. The issue here
       * is that it will sort on the true value, and not the moderated output. So if a view is supposed to return an alias name
       * rather than the true value, but someone uses sortBy on the other bank account name/holder, not only will the returned data
       * have the wrong order, but information about the true account holder name will be exposed due to its position in the sorted order
       *
       * This applies to all fields that can have their data concealed... which in theory will eventually be most/all
       *
       */
      //val sortBy = json.header("obp_sort_by")
      val sortBy = None
      val sortDirection = OBPOrder(json.header("obp_sort_by"))
      val fromDate = tryo{dateFormat.parse(json.header("obp_from_date") getOrElse "")}.map(OBPFromDate(_))
      val toDate = tryo{dateFormat.parse(json.header("obp_to_date") getOrElse "")}.map(OBPToDate(_))

      def getTransactions(bankAccount: BankAccount, view: View, user: Option[User]) = {
        if(bankAccount.authorisedAccess(view, user)) {
          val basicParams = List(OBPLimit(limit),
                          OBPOffset(offset),
                          OBPOrdering(sortBy, sortDirection))

          val params : List[OBPQueryParam] = fromDate.toList ::: toDate.toList ::: basicParams
          bankAccount.getModeratedTransactions(params: _*)(view.moderate)
        } else Nil
      }

      def transactionsJson(transactions : List[ModeratedTransaction], v : View) : JObject = {
        ("transactions" -> transactions.map(transactionJson(_, v)))
      }

      def transactionJson(t : ModeratedTransaction, v : View) : JObject = {
        ("transaction" ->
          ("view" -> v.permalink) ~
          ("uuid" -> t.id) ~
          ("bank_id" -> "") ~
          ("this_account" -> t.bankAccount.map(thisAccountJson)) ~
          ("other_account" -> t.otherBankAccount.map(otherAccountJson)) ~
          ("details" ->
            ("type" -> t.transactionType.getOrElse("")) ~
            ("label" -> t.label.getOrElse("")) ~
            ("posted" -> t.dateOption2JString(t.startDate)) ~
            ("completed" -> t.dateOption2JString(t.finishDate)) ~
            ("new_balance" ->
              ("currency" -> t.currency.getOrElse("")) ~
              ("amount" -> t.balance)) ~
            ("value" ->
              ("currency" -> t.currency.getOrElse("")) ~
              ("amount" -> t.amount))))
      }

      def thisAccountJson(thisAccount : ModeratedBankAccount) : JObject = {
        ("holder" -> thisAccount.owners.flatten.map(ownerJson)) ~
        ("number" -> thisAccount.number.getOrElse("")) ~
        ("kind" -> thisAccount.accountType.getOrElse("")) ~
        ("bank" ->
          ("IBAN" -> thisAccount.iban.getOrElse(Some(""))) ~ //TODO: Why is it Option[Option[String]]?
          ("national_identifier" -> thisAccount.nationalIdentifier.getOrElse("")) ~
          ("name" -> thisAccount.label.getOrElse("")))
      }

      def ownerJson(owner : AccountOwner) : JObject = {
        ("name" -> owner.name) ~
        ("is_alias" -> false)
      }

      def otherAccountJson(otherAccount : ModeratedOtherBankAccount) : JObject = {
        ("holder" ->
          ("name" -> otherAccount.label.display) ~
          ("is_alias" -> otherAccount.isAlias)) ~
        ("number" -> otherAccount.number.getOrElse("")) ~
        ("type" -> "") ~
        ("bank" ->
          ("IBAN" -> "") ~
          ("national_identifier" -> "") ~
          ("name" -> ""))
      }

      val response : Box[JsonResponse] = for {
        bankAccount <- BankAccount(bankId, accountId)
        view <- View.fromUrl(viewId) //TODO: This will have to change if we implement custom view names for different accounts
      } yield {
        val ts = getTransactions(bankAccount, view, getUser(httpCode,oAuthParameters.get("oauth_token")))
        JsonResponse(transactionsJson(ts, view),Nil, Nil, 200)
      }

      response getOrElse (JsonResponse(ErrorMessage(message), Nil, Nil, 401)) : LiftResponse
    }
  })

  serve("obp" / "v1.1" prefix {

    case "banks" :: bankId :: "accounts" :: Nil JsonGet json => {

      //log the API call
      logAPICall

      val (httpCode, message, oAuthParameters) = validator("protectedResource", "GET")
      val headers = ("Content-type" -> "application/x-www-form-urlencoded") :: Nil
      val user = getUser(httpCode,oAuthParameters.get("oauth_token"))

      def viewToJson(v : View) : JObject = {
        ("view" -> (

            ("id" -> v.permalink) ~
            ("short_name" -> v.name) ~
            ("description" -> v.description) ~
            ("is_public" -> v.isPublic)
        ))
      }

      def accountToJson(acc : BankAccount, user : Box[User]) : JObject = {
        //just a log
        user match {
          case Full(u) => logger.info("user " + u.emailAddress + " was found")
          case _ => logger.info("no user was found")
        }

        val views = acc permittedViews user
        ("account" -> (
          ("id" -> acc.permalink) ~
          ("views_available" -> views.map(viewToJson(_)))
        ))
      }
      def bankAccountSet2JsonResponse(bankAccounts: Set[BankAccount]): LiftResponse = {
        val accJson = bankAccounts.map(accountToJson(_,user))
        JsonResponse(("accounts" -> accJson))
      }

      Bank(bankId) match {
        case Full(bank) =>
        {
          if(isThereOauthHeader)
          {
            if(httpCode == 200)
            {
              val availableAccounts = bank.accounts.filter(_.permittedViews(user).size!=0)
              bankAccountSet2JsonResponse(availableAccounts)
            }
            else
              JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
          }
          else
          {
            val availableAccounts = bank.accounts.filter(_.permittedViews(user).size!=0)
            bankAccountSet2JsonResponse(availableAccounts)
          }
        }
        case _ =>  {
          val error = "bank " + bankId + " not found"
          JsonResponse(ErrorMessage(error), Nil, Nil, httpCode)
        }
      }
    }

    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "account" :: Nil JsonGet json => {
      logAPICall
      val (httpCode, message, oAuthParameters) = validator("protectedResource", "GET")
      val headers = ("Content-type" -> "application/x-www-form-urlencoded") :: Nil
      val user = getUser(httpCode, oAuthParameters.get("oauth_token"))

      case class ModeratedAccountAndViews(account: ModeratedBankAccount, views: Set[View])

      val moderatedAccountAndViews = for {
        bank <- Bank(bankId) ?~ { "bank " + bankId + " not found" } ~> 404
        account <- BankAccount(bankId, accountId) ?~ { "account " + accountId + " not found for bank" } ~> 404
        view <- View.fromUrl(viewId) ?~ { "view " + viewId + " not found for account" } ~> 404
        moderatedAccount <- account.moderatedBankAccount(view, user) ?~ { "view/account not authorised" } ~> 401
        availableViews <- Full(account.permittedViews(user))
      } yield ModeratedAccountAndViews(moderatedAccount, availableViews)

      val bankName = moderatedAccountAndViews.flatMap(_.account.bankName) getOrElse ""

      def viewJson(view: View): JObject = {

        val isPublic: Boolean =
          view match {
            case Public => true
            case _ => false
          }

        ("id" -> view.id) ~
        ("short_name" -> view.name) ~
        ("description" -> view.description) ~
        ("is_public" -> isPublic)
      }

      def ownerJson(accountOwner: AccountOwner): JObject = {
        ("user_id" -> accountOwner.id) ~
        ("user_provider" -> bankName) ~
        ("display_name" -> accountOwner.name)
      }

      def balanceJson(account: ModeratedBankAccount): JObject = {
        ("currency" -> account.currency) ~
        ("amount" -> account.balance)
      }

      def json(account: ModeratedBankAccount, views: Set[View]): JObject = {
        ("account" ->
        ("number" -> account.number) ~
        ("owners" -> account.owners.flatten.map(ownerJson)) ~
        ("type" -> account.accountType) ~
        ("balance" -> balanceJson(account)) ~
        ("IBAN" -> account.iban) ~
        ("views_available" -> views.map(viewJson)))
      }

      moderatedAccountAndViews.map(mv => JsonResponse(json(mv.account, mv.views)))
    }

  })
}