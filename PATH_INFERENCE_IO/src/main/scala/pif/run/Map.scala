/**
 * Copyright 2012. The Regents of the University of California (Regents).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package pif.run
import core_extensions.MMLogging
import netconfig.io.Dates
import org.joda.time.LocalDate
import scopt.OptionParser
import netconfig.io.files.ProbeCoordinateViterbi
import netconfig.Link
import netconfig.io.DataSink
import netconfig.io.TrackPiece
import scala.collection.mutable.Queue
import netconfig.Datum.ProbeCoordinate
import netconfig.Datum.PathInference
import core.Time
import netconfig.Route
import netconfig.io.Serializer
import netconfig.io.files.PathInferenceViterbi
import java.io.File
import netconfig.io.files.TrajectoryViterbif
import collection.mutable.{ Map => MMap }
import collection.JavaConversions._
import netconfig_extensions.PathInferenceUtils
import netconfig_extensions.ProbeCoordinateUtils
import netconfig.io.json.NetworkUtils
import netconfig.io.files.TSpotViterbi
import netconfig.io.files.RouteTTViterbi
import netconfig_extensions.CollectionUtils._
import com.google.common.collect.ImmutableList
import netconfig.io.Connection

/**
 * A stateful object that merges streams of ProbeCoordinate and PathInference objects belonging
 * to the same driver.
 */
class Merger[L <: Link](
  traj_fun: (String, Int) => DataSink[TrackPiece[L]],
  val vehicle: String) extends MMLogging {

  var _traj_idx = -1
  var _sink: DataSink[TrackPiece[L]] = null
  val pcs = new Queue[ProbeCoordinate[L]]
  val pis = new Queue[PathInference[L]]
  // The last time sent to the sink
  var current_time: Time = null

  def sink() = {
    if (_sink == null) {
      _traj_idx += 1
      _sink = traj_fun(vehicle, _traj_idx)
    }
    _sink
  }

  def closeSink() {
    if (_sink != null) {
      _sink.close()
      _sink = null
    }
  }

  def addProbeCoordinate(pc: ProbeCoordinate[L]): Unit = {
    pcs += pc
    // Update as far as we can.
    while (update()) {}
  }

  def addPathInference(pi: PathInference[L]): Unit = {
    pis += pi
    // Update as far as we can.
    while (update()) {}
  }

  def update(): Boolean = {
    // Check if we received a PI object to attach to:
    (pis.headOption, pcs.headOption) match {
      case (Some(pi), Some(pc)) => {
        // Checking invariants
        // We require the time to go strictly forward.
        // Some feeds have some peculiar points that get sent instantly.
        if (pi.endTime == pi.startTime) {
          logWarning("The following PI object has a 0 delta:\n%s" format pi.toString)
          if (pc.time == pi.endTime) {
            logWarning("Also discarding the following PC:\n%s" format pc.toString())
          }
          pis.dequeue()
          pcs.dequeue()
          true
        } else {
          if (current_time != null) {
            assert(pi.startTime >= current_time)
            assert(pc.time > current_time)
          }
          if (pc.time < pi.startTime) {
            // There is a disconnect with a single point somewhere
            // We drop this point for now.
            logInfo("Dropping orphan point:\n%s" format pc.toString)
            pcs.dequeue()
            true
          } else if (pc.time == pi.startTime) {
            // There is disconnect in the trajectory.
            // We start a new trajectory.
            // We know this PC is not orphan since there is
            // a PI right behind it.
            closeSink()
            val tp = new {
              val firstConnections: ImmutableList[Connection] = Array.empty[Connection]
              val routes: ImmutableList[Route[L]] = ImmutableList.of[Route[L]]
              val secondConnections: ImmutableList[Connection] = Array.empty[Connection]
              val point = pc
            } with TrackPiece[L]
            sink().put(tp)
            pcs.dequeue()
            //              logInfo("Dequeuing start point:\n%s" format pc.toString())
            current_time = pc.time
            true
          } else {
            // The PC is somewhere after the PI started.
            // It cannot be during the PI
            assert(pc.time >= pi.endTime)
            if (pc.time == pi.endTime) {
              if (current_time != null && pi.startTime == current_time) {
                // We can create a new track piece here.
                // Assuming there is a single projection and
                // a single path (that should be relaxed at some point
                // but easier to reason with).
                assert(pc.spots.size == 1)
                assert(pi.routes.size == 1)
                val tp = new {
                  val firstConnections = ImmutableList.of(new Connection(0, 0))
                  val routes = ImmutableList.of(pi.routes.head)
                  val secondConnections = ImmutableList.of(new Connection(0, 0))
                  val point = pc
                } with TrackPiece[L]
                sink.put(tp)
                pcs.dequeue()
                pis.dequeue()
                //                  logInfo("Dequeuing point and path:\n%s\n" format (pc.toString(), pi.toString()))
                current_time = pc.time
                true
              } else {
                // We missed a PC to start the PI.
                // There is a disconnect that should not have happened.
                logError("Missing a PC before this PI!\nPrevious time: %s\nCurrent PI:\n%s" format (current_time, pi.toString()))
                false
              }
            } else if (pc.time > pi.endTime) {
              // Ooops we lost a PC??
              logError("Received a PC after a PI: \n %s\n===========\n%s\n" format (pc.toString(), pi.toString()))
              assert(false)
              false
            } else {
              // Error case already covered above.
              assert(false)
              false
            }
          }
        }
      }
      case _ => {
        // One of the queues is empty, nothing we can do
        // for now.
        false
      }
    }
  }

  def finish(): Unit = {
    closeSink()
  }
}

object MapDataGeneric extends MMLogging {

  val longHelp = """ Generic data mapper.
This class helps you convert the output of the Path Inference Filter to different other datatypes:
 - RouteTT objects (single routes between points)
 - TSpot objects (unique projection for each GPS points)
 - Track objects (pieces of connected trajectories)

Possible actions:
 - tspot
 - routett
 - traj

This assumes the network uses generic links.
"""

  def main(args: Array[String]) = {
    import Dates._
    // All the options
    var network_id: Int = -1
    var net_type: String = ""
    var actions: Seq[String] = Seq.empty
    var date: LocalDate = null
    var range: Seq[LocalDate] = Seq.empty
    var feed: String = ""
    val parser = new OptionParser("test") {
      intOpt("nid", "the net id", network_id = _)
      opt("date", "the date", { s: String => { for (d <- parseDate(s)) { date = d } } })
      opt("range", "the date", (s: String) => for (r <- parseRange(s)) { range = r })
      opt("feed", "data feed", feed = _)
      opt("actions", "data feed", (s: String) => { actions = s.split(",") })
      opt("net-type", "The type of network", net_type = _)
    }
    parser.parse(args)

    assert(!actions.isEmpty, "You must specify one action or more")
    assert(!net_type.isEmpty, "You must specify a network type or more")
    logInfo("Actions: " + actions mkString " ")
    logInfo("Network type: " + net_type)

    val date_range: Seq[LocalDate] = {
      if (date != null) {
        Seq(date)
      } else {
        range
      }
    }
    assert(!date_range.isEmpty, "You must provide a date or a range of date")
    logInfo("Date range: " + date_range)

    logInfo("About to start")
    logInfo("Actions: %s" format actions.mkString(" "))
    logInfo("Networks: %s" format net_type)

    val serializer = NetworkUtils.getSerializer(net_type, network_id)

    for (action <- actions) {
      action match {
        case "tspot" => for (fidx <- ProbeCoordinateViterbi.list(feed = feed, nid = network_id, net_type = net_type, dates = date_range)) {
          mapTSpot(serializer, net_type, fidx)
        }
        case "traj" => for (fidx <- ProbeCoordinateViterbi.list(feed = feed, nid = network_id, net_type = net_type, dates = date_range)) {
          mapTrajectory(serializer, net_type, fidx)
        }
        case "routett" => for (fidx <- PathInferenceViterbi.list(feed = feed, nid = network_id, net_type = net_type, dates = date_range)) {
          mapRouteTT(serializer, net_type, fidx)
        }
        case _ => {
          logInfo("Unknown action " + action)
        }
      }
    }
    logInfo("Done")
  }

  def mapRouteTT[L <: Link](serializer: Serializer[L], net_type: String,
    findex: PathInferenceViterbi.FileIndex): Unit = {
    val fname_pis = PathInferenceViterbi.fileName(feed = findex.feed, nid = findex.nid,
      date = findex.date,
      net_type = net_type)
    if (!(new File(fname_pis)).exists()) {
      logInfo("File " + fname_pis + " does not exists, skipping.")
      return
    }
    logInfo("Opening for reading : " + fname_pis)
    val data = serializer.readPathInferences(fname_pis)

    val fname_pis_out = RouteTTViterbi.fileName(feed = findex.feed, nid = findex.nid,
      date = findex.date,
      net_type = net_type)
    logInfo("Opening for writing : " + fname_pis_out)
    val writer_pi = serializer.writerPathInference(fname_pis_out)

    for (
      pi <- data;
      rtt <- PathInferenceUtils.projectPathInferenceToRouteTT(pi)
    ) {
      writer_pi.put(rtt.toPathInference)
    }
    writer_pi.close()
  }

  def mapTSpot[L <: Link](serializer: Serializer[L], net_type: String,
    findex: ProbeCoordinateViterbi.FileIndex): Unit = {
    val fname_pcs = ProbeCoordinateViterbi.fileName(feed = findex.feed, nid = findex.nid,
      date = findex.date,
      net_type = net_type)
    if (!(new File(fname_pcs)).exists()) {
      logInfo("File " + fname_pcs + " does not exists, skipping.")
      return
    }
    logInfo("Opening for reading : " + fname_pcs)
    val data = serializer.readProbeCoordinates(fname_pcs)

    val fname_pcs_out = TSpotViterbi.fileName(feed = findex.feed, nid = findex.nid,
      date = findex.date,
      net_type = net_type)
    logInfo("Opening for writing : " + fname_pcs_out)
    val writer_pc = serializer.writerProbeCoordinate(fname_pcs_out)

    for (
      pc <- data;
      tsp <- ProbeCoordinateUtils.projectProbeCoordinateToTSpot(pc)
    ) {
      writer_pc.put(tsp.toProbeCoordinate)
    }
    writer_pc.close()
  }

  def mapTrajectory[L <: Link](serializer: Serializer[L], net_type: String,
    findex: ProbeCoordinateViterbi.FileIndex): Unit = {
    import findex._
    logInfo("Mapping traj: %s" format findex.toString())
    val mg_pcs = {
      val fname_pcs = ProbeCoordinateViterbi.fileName(feed = findex.feed, nid = findex.nid,
        date = findex.date,
        net_type = net_type)
      // This file may not exist.
      if (!(new File(fname_pcs)).exists()) {
        logInfo("File " + fname_pcs + " does not exists, skipping.")
        return
      }
      serializer.readProbeCoordinates(fname_pcs).iterator
    }
    val mg_pis = {
      val fname_pis = PathInferenceViterbi.fileName(feed = findex.feed, nid = findex.nid,
        date = findex.date,
        net_type = net_type)
      // This file may not exist.
      if (!(new File(fname_pis)).exists()) {
        logInfo("File " + fname_pis + " does not exists, skipping.")
        return
      }
      serializer.readPathInferences(fname_pis).iterator
    }
    def writer_trajs_fun(vehicle: String, traj_idx: Int) = {
      val fname_trajs_out = TrajectoryViterbif.fileName(feed, nid, date, net_type = net_type, vehicle, traj_idx)
      serializer.writerTrack(fname_trajs_out)
    }

    // Perform the merge
    val mergers = MMap.empty[String, Merger[L]]

    while (mg_pis.hasNext || mg_pcs.hasNext) {
      if (mg_pis.hasNext) {
        val mg_pi = mg_pis.next()
        val vehicle = mg_pi.id
        val merger = mergers.getOrElseUpdate(vehicle, new Merger(writer_trajs_fun, vehicle))
        merger.addPathInference(mg_pi)
      }
      if (mg_pcs.hasNext) {
        val mg_pc = mg_pcs.next()
        val vehicle = mg_pc.id
        val merger = mergers.getOrElseUpdate(vehicle, new Merger(writer_trajs_fun, vehicle))
        merger.addProbeCoordinate(mg_pc)
      }
    }
    for (merger <- mergers.values) {
      merger.finish()
    }
  }
}