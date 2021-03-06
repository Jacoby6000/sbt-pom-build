package com.jacoby6000.sbt

import java.io.File

import sbt.internal.LoadedPlugins
import scala.xml._
import sbt.util.Logger

object PomBuildPlugins {

  def buildPluginsXml(plugins: LoadedPlugins, ignorePluginNames: Seq[String], ignorePluginClasses: Seq[String], ignoreDirs: Seq[File], logger: Logger) = {
    val detectedAutoPlugins = plugins.detected.autoPlugins

    logger.debug("Beginning very ugly classpath scan for sbt dependencies.  Blame sbt for not providing this information, or if it is provided, blame me for not being aware of it and file an issue.")

    val files = 
      detectedAutoPlugins
        .filterNot { plugin =>
          ignorePluginClasses.find(plugin.name.matches(_)) match {
            case Some(pattern) => 
              logger.debug(s"Ignoring plugin ${plugin.name} (matches pattern `$pattern`)")
              true
            case None => 
              false
          }
        }
        .flatMap { plugin => 
          try {
            val result = Class.forName(plugin.name)
              .getProtectionDomain()
              .getCodeSource()
              .getLocation()
              .getFile()
            
            if(result == null) {
              logger.warn(s"Failed to find location of $plugin. Ignoring.")
              None
            } else {
              Some(result)
            }
          } catch {
            case ex: ClassNotFoundException =>
              logger.error(s"Failed to find class ${plugin.name}. Add ${plugin.name} to `ignorePluginClasses` to get rid of this error, or report a bug if this is an issue.")
              None
          }
        }
        .distinct
        .filterNot { path => 
          val ignore1 = 
            ignoreDirs.find(d => path.startsWith(d.getCanonicalPath())) match {
              case None => false
              case Some(p) =>
                logger.debug(s"Ignoring plugin jar $path (subdirectory of `$p`)")
                true
            }

          val ignore2 = !path.endsWith(".jar")
          if(ignore2)
            logger.debug("Ignoring non-packaged plugin " + path)

          ignore1 || ignore2
        }
        .distinct
        .flatMap { p => 
          val sep = File.separator
          val splits = p.split(sep)
          val lastSegmentNoExt = splits.last.dropRight(4)
          val allButLast = splits.dropRight(1).mkString(sep)
          val pomPath = getCategoryPath(p, "poms").getOrElse(allButLast) + sep + lastSegmentNoExt + ".pom"
          logger.debug(s"Testing pom $pomPath")
          if(new File(pomPath).exists()) {
            logger.debug(s"Found pom")
            Some(pomPath)
          } else {
            logger.debug("No pom found. Testing ivy")
            val ivyPath = getCategoryPath(p, "ivys").getOrElse(allButLast) + sep + "ivy.xml"
            logger.debug(s"Testing ivy $ivyPath")
            if(new File(ivyPath).exists()) {
              logger.debug(s"Found ivy")
              Some(ivyPath)
            } else {
              logger.warn(s"No ivy nor pom found. Could not find artifact metadata for $p")
              None
            }
          }
        }
    
    val pluginsXml = files.flatMap { file =>
      val PomPattern = ".+?.pom$".r
      val IvyPattern = "ivy.xml$".r
      file.split(File.separator).last match {
        case PomPattern() =>
          val xml = XML.loadFile(file) \\ "project"
          val artifactId = (xml \ "artifactId").text
          
          ignorePluginNames.find(artifactId.matches(_)) match {
            case Some(pat) => 
              logger.debug(s"Ignoring plugin $artifactId. (matches pattern `$pat`)")
              None
            case None =>
              val groupId = (xml \ "groupId").text
              val version = (xml \ "version").text
              logger.debug(s"Adding plugin to pom $groupId:$artifactId:$version")
              Some(<plugin><groupId>{groupId}</groupId><artifactId>{artifactId}</artifactId><version>{version}</version></plugin>)
          }
        case IvyPattern() =>
          val xml = XML.loadFile(file) \\ "ivy-module" \ "info"
          val artifactId = (xml \ "@module").text

          ignorePluginNames.find(artifactId.matches(_)) match {
            case Some(pat) => 
              logger.debug(s"Ignoring plugin $artifactId. (matches pattern `$pat`)")
              None
            case None =>
              val groupId = (xml \ "@organisation").text
              val version = (xml \ "@revision").text
              logger.debug(s"Adding plugin to pom $groupId:$artifactId:$version")
              Some(<plugin><groupId>{groupId}</groupId><artifactId>{artifactId}</artifactId><version>{version}</version></plugin>)
          }

        case _ =>
          logger.error("Malformed file path reached file processing.  This is a bug.")
          logger.error("\t\t" + file)
          None
      }
    }

    <plugins>{pluginsXml}</plugins>
  }

  private def getCategoryPath(path: String, category: String) = {
    val sep = File.separator
    val splits = path.split(sep)
    splits.reverse.toList match {
      case last :: "jars" :: rest =>
        Some((splits.dropRight(2) ++ Seq(category)).mkString(sep))
      case _ =>
        None
    }
  }
          
}
