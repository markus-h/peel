package eu.stratosphere.peel.analyser.cli

import java.lang.{System => Sys}
import java.nio.file.Paths
import eu.stratosphere.peel.core.cli.command.Command
import eu.stratosphere.peel.core.beans.experiment.ExperimentSuite
import eu.stratosphere.peel.core.beans.system.{Lifespan, System}
import eu.stratosphere.peel.core.config.{Configurable, loadConfig}
import eu.stratosphere.peel.core.graph.createGraph
import net.sourceforge.argparse4j.impl.Arguments
import net.sourceforge.argparse4j.inf.{Namespace, Subparser}
import org.springframework.context.ApplicationContext
import eu.stratosphere.peel.analyser.controller.ParserManager


class Analyse extends Command {

  override def name() = "analyse"

  override def help() = "analyse the log files generated by peel"

  override def register(parser: Subparser) = {
    // options
    parser.addArgument("--path")
      .`type`(classOf[String])
      .dest("app.peelanalyser.path")
      .metavar("FIXTURES")
      .help("path to the logfile to analyse")
    parser.addArgument("--skipInstances")
      .`type`(classOf[Boolean])
      .dest("app.peelanalyser.skipInstances")
      .action(Arguments.storeTrue)
      .help("skip parsing the details in the logfile. If you make just benchmarking, set this to true")


    // option defaults
    parser.setDefault("app.path.fixtures", "config/fixtures.xml")
  }

  override def configure(ns: Namespace) = {
    // set ns options and arguments to system properties
    Sys.setProperty("app.peelanalyser.path", Paths.get(ns.getString("app.peelanalyser.path")).normalize.toAbsolutePath.toString)
    Sys.setProperty("app.peelanalyser.skipInstances", if (ns.getBoolean("app.peelanalyser.skipInstances")) "true" else "false")
  }

  override def run(context: ApplicationContext) = {
    val path = Sys.getProperty("app.peelanalyser.path")
    val skipInstancesBoolean =  Sys.getProperty("app.peelanalyser.skipInstances").toBoolean

    val parserManager = new ParserManager(path, skipInstancesBoolean)
    parserManager.parsePath()
  }
}
