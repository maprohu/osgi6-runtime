package osgi6.runtime

import java.io.{File, PrintWriter}
import javax.servlet.ServletConfig
import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

import org.osgi.framework.Bundle
import org.osgi.framework.launch.Framework
import org.osgi.framework.wiring.FrameworkWiring
import osgi6.api.{Context, OsgiApi}
import osgi6.common.{HttpTools, OsgiTools}

import scala.concurrent.duration._
import sbt.io.Path._

import scala.util.Try
import scala.util.control.NonFatal

/**
  * Created by pappmar on 23/06/2016.
  */
abstract class OsgiServlet extends HttpServlet {

  def ctx : Context

  def deploy(fw: Framework, jarDir: File) : Unit = {
    OsgiRuntime.deployDefault(fw, jarDir)
  }

  var fw : Framework = null
  var fwClose : () => Unit = () => ()

  override def init(servletConfig: ServletConfig): Unit = {
    OsgiApi.servletConfig = servletConfig
    super.init(servletConfig)
  }

  override def init(): Unit = {
    super.init()
    startFramework
  }

  def startFramework = synchronized {
    val cont = ctx
    val jarDir = cont.data / "jars"
    val (fw0, fwClose0) = OsgiRuntime.init(ctx, fw => deploy(fw, jarDir))
    fw = fw0
    fwClose = fwClose0
  }

  def shutdownFramework = synchronized {
    if (fw != null) {
      Try(fw.stop())
      Try(fw.waitForStop(30.seconds.toMillis))
      val state = fw.getState
      fw = null
      Try(fwClose())
      fwClose = null
      state
    } else {
      99
    }
  }

  def restartFramework = synchronized {
    shutdownFramework
    startFramework
  }

  override def destroy(): Unit = {
    shutdownFramework
    super.destroy()
  }

  def getStateString(bundle: Bundle) : String = {
    getStateString(bundle.getState)
  }
  def getStateString(state: Int) : String = {
      if (state == 32) "Active     "
      else if (state == 2) "Installed  "
      else if (state == 4) "Resolved   "
      else if (state == 8) "Starting   "
      else if (state == 16) "Stopping   "
      else "Unknown    "
  }

  override def service(req: HttpServletRequest, resp: HttpServletResponse): Unit = {

    def processAdminRequest(fn: => String) = {
      resp.setContentType("text/plain")
      HttpTools.preResponse(req, resp)
      val os = resp.getOutputStream
      try {
        try {
          resp.getOutputStream.print(fn)
        } catch {
          case NonFatal(ex) =>
            val pw = new PrintWriter(resp.getOutputStream)
            ex.printStackTrace(pw)
            pw.close()
        }
      } finally {
        os.close()
      }

    }

    val servletPath = Option(req.getServletPath)

    servletPath match {
      case Some("/_admin/deploy") =>
        processAdminRequest {
          OsgiTools.deployBundle(
            fw.getBundleContext,
            req.getInputStream
          )
        }
      case Some("/_admin/list") =>
        processAdminRequest {
          fw
            .getBundleContext
            .getBundles
            .map({ bundle =>
              f"${bundle.getBundleId}%4d - ${getStateString(bundle)} - ${bundle.getSymbolicName} / ${bundle.getVersion}"
            })
            .mkString("\n")
        }
      case Some("/_admin/undeploy") =>
        processAdminRequest {
          OsgiTools.undeployBundle(
            fw.getBundleContext,
            Option(req.getParameter("id"))
              .getOrElse(throw new RuntimeException("'id' parameter missing"))
              .toLong
          )
        }
      case Some("/_admin/refresh") =>
        processAdminRequest {
          OsgiTools.refresh(fw)
          "bundles refreshed"
        }
      case Some("/_admin/shutdown") =>
        processAdminRequest {
          getStateString(shutdownFramework)
        }
      case Some("/_admin/version") =>
        processAdminRequest {
          s"""${getClass.getName}: ${getClass.getPackage.getImplementationTitle} - ${getClass.getPackage.getImplementationVersion}
             |${OsgiRuntime.getClass.getName}: ${OsgiRuntime.getClass.getPackage.getImplementationTitle} - ${OsgiRuntime.getClass.getPackage.getImplementationVersion}
           """.stripMargin
        }
      case Some("/_admin/restart") =>
        processAdminRequest {
          restartFramework
          "framework restarted"
        }


      case _ =>
        OsgiApi.registry.first.process(req, resp)
    }
  }
}
