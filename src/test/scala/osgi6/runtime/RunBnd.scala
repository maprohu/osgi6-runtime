package osgi6.runtime

import java.io.{DataInputStream, File, FileInputStream}
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.zip.ZipInputStream

/**
  * Created by pappmar on 04/08/2016.
  */
object RunBnd {

//  val target = new File("../osgi6/bundles/h2gis/target/osgi-bundle.jar")
//  val target = new File("../wupdata-osgi/bundles/geoserver/target/osgi-bundle.jar")
  val target = new File("../osgi6-geoserver/libs/geotools/target/osgi-bundle.jar")

  def main(args: Array[String]) {


    val jar = new JarFile(target)

    val manifest =
      jar
        .getManifest

    println(
      manifest
        .getMainAttributes
        .getValue("Bundle-ClassPath")
        .split(',')
        .toSeq
        .drop(1)
        .map({ f =>
          val e = jar.getEntry(f)



          val emb = new ZipInputStream(jar.getInputStream(e))
          val dis = new DataInputStream(emb)

          Iterator
            .continually(emb.getNextEntry)
            .takeWhile(_ != null)
            .filter(_.getName.endsWith(".class"))
            .map({ c =>
              val magic = dis.readInt()
              if(magic != 0xcafebabe) {
                println(c + " is not a valid class!")
              }
              val minor = dis.readUnsignedShort()
              val major = dis.readUnsignedShort()

              (f, major, minor)
            })
            .maxBy(_._2)
        })
        .filter(_._2 > 50)
        .mkString("\n")
    )

  }

}
