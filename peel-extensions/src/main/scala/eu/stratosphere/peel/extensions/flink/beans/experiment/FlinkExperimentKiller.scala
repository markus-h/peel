package eu.stratosphere.peel.extensions.flink.beans.experiment

import java.io.FileWriter
import java.nio.file.{Files, Paths}

import java.lang.{System => Sys}
import com.typesafe.config.Config
import eu.stratosphere.peel.core.beans.data.{DataSet, ExperimentOutput}
import eu.stratosphere.peel.core.beans.experiment.Experiment
import eu.stratosphere.peel.core.util.shell
import eu.stratosphere.peel.extensions.flink.beans.system.Flink
import spray.json._
import scala.sys.process.{Process, ProcessLogger}

/** Flink Experiment class
  *
  * Run Experiments in Flink.
 *
 * @param command The command that specifies the execution of the experiment in terms of the underlying system's way of
 *                submitting jobs. Example command for a Flink-experiment:
 *
 *                <code>-p 16 ./examples/flink-java-examples-0.7.0-incubating-WordCount.jar
 *                file:///home/user/hamlet.txt file:///home/user/wordcount_out
 *                </code>
 *
 *                You do not have to state the command that is used to 'run' the command (e.g. in Flink
 *                <code> ./bin/flink run </code>
 *
 * @param runner The system that is used to run the experiment (e.g. Flink, Spark, ...)
 * @param runs The number of runs/repetitions of this experiment
 * @param inputs Input Datasets for the experiment
 * @param outputs The output of the Experiment
 * @param name Name of the Experiment
 * @param config Config Object for the experiment
 */
class FlinkExperimentKiller(command: String,
                      runner: Flink,
                      runs: Int,
                      inputs: Set[DataSet],
                      outputs: Set[ExperimentOutput],
                      name: String,
                      config: Config) extends Experiment(command, runner, runs, inputs, outputs, name, config) {

  def this(runs: Int, runner: Flink, input: DataSet, output: ExperimentOutput, command: String, name: String, config: Config) = this(command, runner, runs, Set(input), Set(output), name, config)

  def this(runs: Int, runner: Flink, inputs: Set[DataSet], output: ExperimentOutput, command: String, name: String, config: Config) = this(command, runner, runs, inputs, Set(output), name, config)

  override def run(id: Int, force: Boolean): Experiment.Run[Flink] = new FlinkExperimentKiller.SingleJobRun(id, this, force)
}

object FlinkExperimentKiller {

  case class State(name: String,
                   suiteName: String,
                   command: String,
                   runnerName: String,
                   runnerVersion: String,
                   var runExitCode: Option[Int] = None,
                   var runTime: Long = 0,
                   var plnExitCode: Option[Int] = None) extends Experiment.RunState {}

  object StateProtocol extends DefaultJsonProtocol with NullOptions {
    implicit val stateFormat = jsonFormat8(State)
  }

  /** A private inner class encapsulating the logic of single run. */
  class SingleJobRun(val id: Int, val exp: FlinkExperimentKiller, val force: Boolean) extends Experiment.SingleJobRun[Flink, State] {

    import eu.stratosphere.peel.extensions.flink.beans.experiment.FlinkExperimentKiller.StateProtocol._

    val runnerLogPath = exp.config.getString("system.flink.path.log")
    
    val nodeToKill = exp.config.getString("system.flink.kill")
    val nodeToKillLog = exp.config.getString("system.flink.killLog")

    override def isSuccessful = state.runExitCode.getOrElse(-1) == 0 //state.plnExitCode.getOrElse(-1) == 0 && state.runExitCode.getOrElse(-1) == 0

    override protected def logFilePatterns = List(s"$runnerLogPath/flink-*.log", s"$runnerLogPath/flink-*.out")

    override protected def loadState(): State = {
      if (Files.isRegularFile(Paths.get(s"$home/state.json"))) {
        try {
          io.Source.fromFile(s"$home/state.json").mkString.parseJson.convertTo[State]
        } catch {
          case e: Throwable => State(name, Sys.getProperty("app.suite.name"), command, exp.runner.name, exp.runner.version)
        }
      } else {
        State(name, Sys.getProperty("app.suite.name"), command, exp.runner.name, exp.runner.version)
      }
    }

    override protected def writeState() = {
      val fw = new FileWriter(s"$home/state.json")
      fw.write(state.toJson.prettyPrint)
      fw.close()
    }

    override protected def runJob() = {
      // try to get the experiment run plan
      val (plnExit, _) = Experiment.time(this !(s"info -e $command", s"$home/run.pln", s"$home/run.pln"))
      state.plnExitCode = Some(plnExit)
      
      logger.info(s"Killing Node $nodeToKill listening at $runnerLogPath/$nodeToKillLog")
      val killerCommand = "ssh "+nodeToKill+"; sh -c 'tail -n +0 --pid=$$ -F "+s"$runnerLogPath/$nodeToKillLog"+" | { sed \"/starting iteration \\[7\\]/ q\" && kill $$ ;}'; kill $(ps aux | grep 'org.apache.flink.runtime.taskmanager.TaskManager ' -m1 | awk '{print $2}')"
      logger.info(s"$killerCommand")
      // run detached killer process
      val killer = Process("/bin/bash", Seq("-c", killerCommand))
      val killerP = killer.run
      
      // try to execute the experiment run plan
      val (runExit, t) = Experiment.time(this !(s"run $command", s"$home/run.out", s"$home/run.err"))
      state.runTime = t
      state.runExitCode = Some(runExit)
    }

    override def cancelJob() = {
      val ids = (shell !! s"${exp.config.getString("system.flink.path.home")}/bin/flink list -r | tail -n +2 | head -n 1 | cut -d':' -f4 | tr -d ' '").split(Array('\n', ' '))
      for (id <- ids) shell ! s"${exp.config.getString("system.flink.path.home")}/bin/flink cancel -i $id"
      state.runTime = exp.config.getLong("experiment.timeout") * 1000
      state.runExitCode = Some(-1)
    }

    private def !(command: String, outFile: String, errFile: String) = {
      shell ! s"${exp.config.getString("system.flink.path.home")}/bin/flink $command > $outFile 2> $errFile"
    }
  }

}
