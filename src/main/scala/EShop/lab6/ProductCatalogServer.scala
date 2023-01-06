package EShop.lab6

import EShop.lab5.ProductCatalog.{GetItems, Item}
import EShop.lab5.Server.Response
import EShop.lab5.{ProductCatalog, SearchService, Server}
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.actor.typed.scaladsl.{Behaviors, Routers}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives.{as, complete, entity, path, post}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat}

import java.net.URI
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.io.StdIn
import scala.util.Try

trait JsonSupportProductCatalog
    extends SprayJsonSupport
    with DefaultJsonProtocol {
  implicit lazy val requestFormat = jsonFormat2(Server.Request)
  implicit lazy val uriFormat = new JsonFormat[java.net.URI] {
    override def write(obj: java.net.URI): spray.json.JsValue =
      JsString(obj.toString)

    override def read(json: JsValue): URI =
      json match {
        case JsString(url) => new URI(url)
        case _             => throw new RuntimeException("Parsing exception")
      }
  }
  implicit lazy val itemFormat = jsonFormat5(Item)
  implicit lazy val responseFormat = jsonFormat1(Server.Response)
}

object ProductCatalogServer extends App {
  val productCatalogServer = new ProductCatalogServerPool()
  productCatalogServer.run(Try(args(0).toInt).getOrElse(9000))
}

class ProductCatalogServerPool extends JsonSupportProductCatalog {

  implicit val system = ActorSystem(Behaviors.empty, "ReactiveRouters")
//  implicit val scheduler = system.scheduler
  implicit val executionContext = system.executionContext
  val searchService = new SearchService()
  val workers: ActorRef[ProductCatalog.Query] = system.systemActorOf(
    Routers.pool(5)(ProductCatalog(searchService)),
    "workersRouter")

  implicit val timeout: Timeout = 5.seconds

  def routes: Route =
    path("search") {
      post {
        entity(as[Server.Request]) { request =>
          val awaitable = workers.ask(ref =>
            GetItems(request.brand, request.productKeyWords, ref))
          val result = Await.result(awaitable, 2.seconds)
          complete {
            Future.successful(
              Response(result.asInstanceOf[ProductCatalog.Items].items))
          }
        }
      }
    }

  def run(port: Int): Unit = {
    val bindingFuture = Http().newServerAt("localhost", port).bind(routes)
    println(
      s"Server now online. Please navigate to http://localhost:8080/hello\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }

}
