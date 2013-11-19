package mesosphere.marathon.api.v1

import scala.collection.JavaConverters._
import javax.ws.rs._
import mesosphere.marathon.MarathonSchedulerService
import javax.ws.rs.core.{Context, Response, MediaType}
import javax.inject.{Named, Inject}
import javax.validation.Valid
import com.codahale.metrics.annotation.Timed
import com.google.common.eventbus.EventBus
import mesosphere.marathon.event.{EventModule, ApiPostEvent}
import javax.servlet.http.HttpServletRequest
import mesosphere.marathon.tasks.TaskTracker
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import java.util.logging.Logger
import org.apache.mesos.Protos.TaskID

/**
 * @author Tobi Knaup
 */
@Path("v1/apps")
@Produces(Array(MediaType.APPLICATION_JSON))
class AppsResource @Inject()(
    @Named(EventModule.busName) eventBus: Option[EventBus],
    service: MarathonSchedulerService,
    taskTracker: TaskTracker) {

  val defaultWait = Duration(5, "seconds")

  val log = Logger.getLogger(getClass.getName)

  @GET
  @Timed
  def index = {
    service.listApps
  }

  @POST
  @Path("start")
  @Timed
  def start(@Context req: HttpServletRequest, @Valid app: AppDefinition): Response = {
    maybePostEvent(req, app)
    Await.result(service.startApp(app), defaultWait)
    Response.noContent.build
  }

  @POST
  @Path("stop")
  @Timed
  def stop(@Context req: HttpServletRequest, app: AppDefinition): Response = {
    maybePostEvent(req, app)
    Await.result(service.stopApp(app), defaultWait)
    Response.noContent.build
  }

  @POST
  @Path("scale")
  @Timed
  def scale(@Context req: HttpServletRequest, app: AppDefinition): Response = {
    maybePostEvent(req, app)
    Await.result(service.scaleApp(app), defaultWait)
    Response.noContent.build
  }

  private def maybePostEvent(req: HttpServletRequest, app: AppDefinition) {
    if (eventBus.nonEmpty) {
      val ip = req.getRemoteAddr
      val path = req.getRequestURI
      eventBus.get.post(new ApiPostEvent(ip, path, app))
    }
  }

  @GET
  @Path("search")
  @Timed
  def search(@QueryParam("id") id: String,
             @QueryParam("cmd") cmd: String) = {
    service.listApps.filter {
      x =>
        var valid = true
        if (id != null && !id.isEmpty && !x.id.toLowerCase.contains(id.toLowerCase)) {
          valid = false
        }
        if (cmd != null && !cmd.isEmpty && !x.cmd.toLowerCase.contains(cmd.toLowerCase)) {
          valid = false
        }
        // Maybe add some other query parameters?
        valid
    }
  }

  import Implicits._

  @GET
  @Path("{appId}/tasks")
  @Timed
  def app(@PathParam("appId") appId: String): Response = {
    val tasks = taskTracker.get(appId)
    val result = Map(appId -> tasks.map(s => s: Map[String, Object]))
    if (tasks.size > 0) {
      Response.ok(result).build
    } else {
      Response.noContent.status(404).build
    }
  }

  @POST
  @Path("{appId}/scale")
  @Timed
  def scale(@PathParam("appId") appId: String,
            @QueryParam("host") host: String,
            @QueryParam("id") id: String) = {
    service.getApp(appId) match {
      case Some(appDef) =>
        val tasks = taskTracker.get(appId)
        val toKill = tasks.filter(x => x.getHost == host || x.getId == id)
        appDef.instances = appDef.instances - toKill.size

        Await.result(service.scaleApp(appDef, false), defaultWait)

        for (task <- toKill) {
          log.info(f"Killing task ${task.getId} on host ${task.getHost}")
          service.driver.killTask(TaskID.newBuilder.setValue(task.getId).build)
        }
      case None =>
    }
  }
}
