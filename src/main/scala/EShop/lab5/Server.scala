package EShop.lab5

import EShop.lab5.ProductCatalog.{GetItems, Item, ProductCatalogServiceKey}
import EShop.lab5.Server.{Command, Listing, Response}
import akka.Done
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.remote.transport.ActorTransportAdapter.AskTimeout
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat}

import java.net.URI
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

object Server {
  sealed trait Command

  case class Listing(listing: Receptionist.Listing) extends Command

  case class Request(brand: String, productKeyWords: List[String])

  case class Response(items: List[Item])
}

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit lazy val requestFormat = jsonFormat2(Server.Request)
  implicit lazy val uriFormat = new JsonFormat[java.net.URI] {
    override def write(obj: java.net.URI): spray.json.JsValue = JsString(obj.toString)

    override def read(json: JsValue): URI =
      json match {
        case JsString(url) => new URI(url)
        case _ => throw new RuntimeException("Parsing exception")
      }
  }
  implicit lazy val itemFormat = jsonFormat5(Item)
  implicit lazy val responseFormat = jsonFormat1(Server.Response)
}

object ServerApp extends App {
  new Server().start(9000)
}

class Server extends JsonSupport {
  implicit val system = ActorSystem[Nothing](Behaviors.empty, "HelloWorldAkkaHttp")
  implicit var productCatalog: ActorRef[ProductCatalog.Query] = _

  def routes: Route = {
    path("search") {
      post {
        entity(as[Server.Request]) { request =>
          println(request)
          val awaitable = productCatalog.ask(ref => GetItems(request.brand, request.productKeyWords, ref))
          val result = Await.result(awaitable, 2 seconds)

          complete {
            Future.successful(Response(result.asInstanceOf[ProductCatalog.Items].items))
          }
        }
      }
    }
  }

  def apply(port: Int): Behavior[Command] = {
    Behaviors.setup[Server.Command] { context =>
      implicit val system: ActorSystem[Nothing] = context.system
      val adapter = context.messageAdapter[Receptionist.Listing](Server.Listing)
      system.receptionist ! Receptionist.subscribe(ProductCatalogServiceKey, adapter)
      Behaviors.receiveMessage {
        case Listing(ProductCatalogServiceKey.Listing(listing)) =>
          if (listing.isEmpty) {
            Behaviors.same
          } else {
            productCatalog = listing.head
            Http().newServerAt("localhost", port).bind(routes)
            Behaviors.same
          }
      }
    }
  }

  def start(port: Int): Future[Done] = {
    implicit val system: ActorSystem[Command] = ActorSystem[Command](apply(port), "ProductCatalog")
    Await.ready(system.whenTerminated, Duration.Inf)
  }

}