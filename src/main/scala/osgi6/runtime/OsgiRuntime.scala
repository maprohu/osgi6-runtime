package osgi6.runtime

import java.io.{File, FileInputStream}
import java.net.{URL, URLClassLoader}
import java.util
import java.util.Properties

import org.osgi.framework.Constants
import org.osgi.framework.launch.{Framework, FrameworkFactory}
import org.osgi.framework.startlevel.BundleStartLevel
import org.osgi.service.startlevel.StartLevel
import osgi6.api.{Context, OsgiApi}
import osgi6.common.OsgiTools
import sbt.io.IO
import sun.misc.Service
import sbt.io.Path._

import scala.collection.JavaConversions._
import scala.util.Try

/**
  * Created by pappmar on 22/06/2016.
  */
object OsgiRuntime {

  case class Ctx(
    name: String,
    version: Int,
    data: File,
    log: File,
    debug: Boolean,
    stdout: Boolean = false,
    init: File => Unit = _ => (),
    console: Boolean = false
  ) extends Context

  def context(dir: File, app: String, version: Int = -1) : Ctx = {

    val data = dir / "data" / app
    val log = dir / "logs"

    Ctx(
      name = app,
      version = version,
      data = data,
      log = log,
      debug = false
    )
  }


  val versionFileName = "version.txt"
  val startupPropertiesFileName = "startup.properties"

  def init(
    ctx: Context,
    deploy: Framework => Unit,
    plusPackages : Option[String] = None
  ) : (Framework, () => Unit)  = {
    val fwf = { () =>
      val jarDir = ctx.data / "jars"
      loadFelix(jarDir)
    }

    init(
      fwf,
      ctx,
      deploy,
      plusPackages
    )
  }

  def init(
    fwff : () => (FrameworkFactory, () => Unit),
    ctx: Context,
    deploy: Framework => Unit,
    plusPackages : Option[String]
  ) : (Framework, () => Unit) = {

    OsgiApi.context = ctx

    val data = ctx.data

    val versionFile = data / versionFileName

    def writeVersion : Unit = {
      Some(ctx.version).filter(_ != -1).foreach { version =>
        IO.write(versionFile, version.toString)
      }
    }

    def readVersion : Option[Int] = {
      Try(IO.read(versionFile).trim.toInt).toOption
    }

    val first = !data.exists() ||
      Some(ctx.version).filter(_ != -1).map { softwareVersion =>
        if (readVersion.forall( { dataFoundVersion =>
          dataFoundVersion < softwareVersion
        })) {
          IO.delete(data)
          true
        } else {
          false
        }
      }.getOrElse(false)

    if (first) {
      data.mkdirs()

      writeVersion
    }

//    """
//      |javax.naming,
//      |javax.naming.*,
//      |javax.xml,
//      |javax.xml.*,
//      |org.xml,
//      |org.xml.*,
//      |org.w3c,
//      |org.w3c.*,
//      |sun.misc,
//      |sun.security.util,
//      |sun.security.x509,
//      |com.singularity.*
//    """.stripMargin.replaceAll("\\s", ""),

//    """
//      |osgi6.api,
//      |javax.servlet;version="2.5.0",
//      |javax.servlet.descriptor;version="2.5.0",
//      |javax.servlet.http;version="2.5.0"
//      |""".stripMargin.replaceAll("\\s", ""),

    val systemPackagesExtraBase =
        """
          |org.w3c.dom.css,
          |org.w3c.dom.html,
          |org.w3c.dom.ranges,
          |org.w3c.dom.stylesheets,
          |org.w3c.dom.traversal,
          |org.w3c.dom.views,
          |org.w3c.dom.xpath,
          |osgi6.api
          |""".stripMargin

    val systemPackagesExtra =
      plusPackages.map({ pp =>
        s"$systemPackagesExtraBase,$pp"
      }).getOrElse(
        systemPackagesExtraBase
      )

    val props = Map[String, String](
      Constants.FRAMEWORK_STORAGE -> (data / "felix-cache").getAbsolutePath,
      Constants.FRAMEWORK_BOOTDELEGATION ->
        """
          |com.sun,
          |com.sun.*,
          |sun.misc,
          |sun.awt,
          |sun.awt.*,
          |sun.java2d,
          |sun.java2d.*,
          |sun.security.util,
          |sun.security.x509,
          |com.singularity,
          |com.singularity.*
        """.stripMargin.replaceAll("\\s", ""),
      Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA ->
        systemPackagesExtra.replaceAll("\\s", "")
//      "obr.repository.url" -> (data / "repo" / "repository.xml").toURI.toString,
//      "gosh.args" -> ("--noshutdown " + (if (ctx.console) "" else "--nointeractive"))
    )

    val propsFile = data / startupPropertiesFileName

    val props2 =
      if (propsFile.exists()) {
        val p = new Properties()
        val ps = new FileInputStream(propsFile)
        try {
          p.load(ps)
        } finally {
          ps.close()
        }
        props ++ p
      } else {
        props
      }

//    val factory = Service.providers(classOf[FrameworkFactory]).asInstanceOf[java.util.Iterator[FrameworkFactory]]
//    val fw = factory.next().newFramework(props)

    val (fwf, fwClose) = fwff()

    try {

      val fw = fwf.newFramework(new util.HashMap(props2))

      try {
        fw.init()

        if (first) {
          deploy(fw)
        }

        fw.start()

        (fw, fwClose)
      } catch {
        case ex : Throwable =>
          Try {
            fw.stop()
          }

          throw ex
      }
    } catch {
      case ex : Throwable =>
        Try {
          fwClose()
        }

        throw ex
    }

  }


  val defaultBundles = Seq(
    "servlet.jar",
    "logging.jar",
    "multi-api.jar",
    "multi-bundle.jar",
    "strict-api.jar",
    "strict-bundle.jar",
//    "jolokia.jar",
    "admin.jar"
  )

  def deployDefault(fw: Framework, jarDir : File) : Unit = {
    deployBundles(
      fw,
      OsgiRuntime.getClass,
      defaultBundles,
      jarDir
    )
  }

  def deployBundles(fw: Framework, clazz: Class[_], bundles: Seq[String], jarDir: File) : Unit = {

    bundles.foreach({ name =>
      OsgiTools.deployBundle0(
        fw.getBundleContext,
        clazz.getResourceAsStream(name)
      )
    })

//    OsgiTools.deploy(
//      fw,
//      bundles.map({ jar =>
//        val outFile = jarDir / jar
//        IO.transfer(clazz.getResourceAsStream(jar), jarDir / jar)
//
//        outFile.toURI.toURL
//      }):_*
//    )

  }


  def loadFelix(jarDir: File) : (FrameworkFactory, () => Unit) = {
    val clazz = OsgiRuntime.getClass

    val felixJar = jarDir / "felix.jar"

    jarDir.mkdirs()

    IO.transfer(clazz.getResourceAsStream("felix.jar"), felixJar)

    // need to load felix with a urlclassloader in order to
    // be able to add fragments to the system bundle
    val cl = new URLClassLoader(
      Array(
        felixJar.toURI.toURL
      ),
      clazz.getClassLoader
    )

    (
      cl.loadClass("org.apache.felix.framework.FrameworkFactory").newInstance().asInstanceOf[FrameworkFactory],
      () => ()
//      () => cl.close()
    )
  }

}
