import com.twitter.finagle.Http
import com.twitter.finagle.http.filter.Cors
import com.twitter.finagle.http.filter.Cors.HttpFilter
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finatra.http.filters.{CommonFilters, LoggingMDCFilter, TraceIdMDCFilter}
import com.twitter.finatra.http.routing.HttpRouter
import com.twitter.finatra.http.{Controller, HttpServer}
import org.postgresql.util.PGInterval
import scalikejdbc._

import scala.collection.immutable.HashMap
import scala.collection.mutable

class RoutingController extends Controller {

  Class.forName("org.postgresql.Driver")

  ConnectionPool.singleton("jdbc:postgresql://localhost:5432/pqdefault", "admin", "pass")
  implicit val session = AutoSession

  def shortest_path(to: String, distances: mutable.HashMap[String, Double], previous: mutable.HashMap[String, String], acc: List[String]): List[String] =
    if(distances(to) != Double.PositiveInfinity) {
      val stop_name = SQL(s"SELECT stop_name FROM stops WHERE stop_id = '${to}'").map(_.toMap()).list().apply()
      shortest_path(previous(to), distances, previous, acc :+ stop_name.head("stop_name").toString)
    } else acc


  def dijkstra(from: String) = {
    val distances = new mutable.HashMap[String, Double] {
      override def default(key: String) = Double.PositiveInfinity
    }
    val previous = new mutable.HashMap[String, String]() {
      override def default(key: String) = ""
    }
    val visited = new mutable.HashMap[String, Boolean]() {
      override def default(key: String): Boolean = false
    }
    val queue = new mutable.PriorityQueue[(String, Boolean, Double)]()(
      (x: (String, Boolean, Double), y: (String, Boolean, Double)) => -x._3.compare(y._3))
    distances += (from -> 0)
    queue += new Tuple3(from, false, 0.0)
    while(queue.nonEmpty) {
      val current_stop = queue.dequeue()
        val neighbors = SQL(s"""
           SELECT * FROM nearest_stops('${current_stop._1}', '11:00:00'::INTERVAL + ROUND(${current_stop._3})::VARCHAR::INTERVAL, ${current_stop._2})
          """)
          .map(_.toMap).list().apply()
        for(neighbor <- neighbors) {
          val alt_time = current_stop._3 + neighbor("travel_time").asInstanceOf[PGInterval].getSeconds + neighbor("travel_time").asInstanceOf[PGInterval].getMinutes * 60.0
          if(alt_time < distances(neighbor("id").toString))
          {
            distances += (neighbor("id").toString -> alt_time)
            previous += (neighbor("id").toString -> current_stop._1)
            queue += new Tuple3(neighbor("id").toString, neighbor("moved").asInstanceOf[Boolean], alt_time)
          }
        }
    }
    HashMap("distances" -> distances, "previous" -> previous)
  }

  get("/nearest_stop/:from_lat/:from_lng") {
    request: Request => {
      val from_lat = request.getParam("from_lat").toFloat
      val from_lng = request.getParam("from_lng").toFloat
      val from = SQL(s"SELECT " +
        s"stop_id sid, ROUND(ST_Distance(geom::geography, ST_SetSRID(ST_Point($from_lng, $from_lat)::geography, 4326)) / 1.4)::VARCHAR::INTERVAL travel_time" +
        s" FROM stops ORDER BY ST_Distance(geom::geography, ST_SetSRID(ST_Point($from_lng, $from_lat)::geography, 4326)) ASC LIMIT 1;").map(_.toMap()).list().apply()
      scala.util.parsing.json.JSONObject(HashMap("id" -> from.head("sid"), "travel_time" -> from.head("travel_time")))
    }
  }

  get("/dijkstra/:from_lat/:from_lng") {
    request: Request => {
      val from_lat = request.getParam("from_lat").toFloat
      val from_lng = request.getParam("from_lng").toFloat
      val from = SQL(s"SELECT " +
        s"stop_id sid, ROUND(ST_Distance(geom::geography, ST_SetSRID(ST_Point($from_lng, $from_lat)::geography, 4326)) / 1.4)::VARCHAR::INTERVAL travel_time" +
        s" FROM stops ORDER BY ST_Distance(geom::geography, ST_SetSRID(ST_Point($from_lng, $from_lat)::geography, 4326)) ASC LIMIT 1;").map(_.toMap()).list().apply()

      val from_sid = from(0).asInstanceOf[Map[String, String]]("sid")
      val from_time = from.head.asInstanceOf[Map[String, PGInterval]]("travel_time").getSeconds
      scala.util.parsing.json.JSONObject(dijkstra(from_sid))
    }
  }

  get("/heatmap/:lat/:lng/:viewport_from_lat/:viewport_from_lng/:viewport_to_lat/:viewport_to_lng/:step") {
    request: Request => {
      val lat = request.getParam("lat").toFloat
      val lng = request.getParam("lng").toFloat
      val from_lat = request.getParam("viewport_from_lat").toFloat
      val from_lng = request.getParam("viewport_from_lng").toFloat
      val to_lat = request.getParam("viewport_to_lat").toFloat
      val to_lng = request.getParam("viewport_to_lng").toFloat
      val step = request.getParam("step").toFloat

      val from = SQL(s"SELECT " +
        s"stop_id sid, ROUND(ST_Distance(geom::geography, ST_SetSRID(ST_Point($lng, $lat)::geography, 4326)) / 1.4)::VARCHAR::INTERVAL travel_time" +
        s" FROM stops ORDER BY ST_Distance(geom::geography, ST_SetSRID(ST_Point($lng, $lat)::geography, 4326)) ASC LIMIT 1;").map(_.toMap()).list().apply()

      val from_sid = from(0).asInstanceOf[Map[String, String]]("sid")
      val from_time = from.head.asInstanceOf[Map[String, PGInterval]]("travel_time").getSeconds

      var result: List[Map[String, AnyVal]] = List()
      val dijkstra_result = dijkstra(from_sid)
      for(x <- from_lat to to_lat by ((to_lat - from_lat) / step)) {
        for(y <- from_lng to to_lng by ((to_lng - from_lng) / step)) {
          val to = SQL(s"SELECT " +
            s"stop_id sid, ROUND(ST_Distance(geom::geography, ST_SetSRID(ST_Point($y, $x)::geography, 4326)) / 1.4)::VARCHAR::INTERVAL travel_time" +
            s" FROM stops ORDER BY ST_Distance(geom::geography, ST_SetSRID(ST_Point($y, $x)::geography, 4326)) ASC LIMIT 1;").map(_.toMap()).list().apply()

          val to_sid = to(0).asInstanceOf[Map[String, String]]("sid")
          val to_time = to.head.asInstanceOf[Map[String, PGInterval]]("travel_time").getSeconds
          val time = dijkstra_result("distances")(to_sid).asInstanceOf[Double]
          result = result :+
            Map("lat" -> x,
              "lng" -> y,
              "value" -> (if ((time + from_time + to_time) == Double.PositiveInfinity) Double.MaxValue else time + from_time + to_time))
        }
      }
      result
    }
  }

  options("/:*") {
    _: Request => response.ok
  }
}

object App extends MyHttpServer

class MyHttpServer extends HttpServer {
  override val defaultFinatraHttpPort: String = ":8080"
  override protected def configureHttp(router: HttpRouter): Unit = {
    router
      .filter(new HttpFilter(Cors.UnsafePermissivePolicy))
      .filter[LoggingMDCFilter[Request, Response]]
      .filter[TraceIdMDCFilter[Request, Response]]
      .filter[CommonFilters].
      add[RoutingController]
  }
/*
  override protected def configureHttpServer(server: Http.Server): Http.Server = {
    server
      .withAdmissionControl.concurrencyLimit(
      maxConcurrentRequests = 3000,
      maxWaiters = 100
    )
  }*/
}


