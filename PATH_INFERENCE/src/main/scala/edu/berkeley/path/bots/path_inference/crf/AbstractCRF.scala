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

package path_inference.crf

import collection.mutable.Queue
import edu.berkeley.path.bots.core.Time
import edu.berkeley.path.bots.core.MMLogging
import edu.berkeley.path.bots.netconfig.Datum.ProbeCoordinate
import edu.berkeley.path.bots.netconfig.Link
import path_inference.Delta
import path_inference.models.ObservationModel
import path_inference.models.TransitionModel
import scalala.tensor.dense.DenseVectorCol
import scalala.operators.Implicits._

/**
 * This is the core class that implements all the math logic, and it is probably
 * one of the most complicated of the path inference project.
 * TODO: carefully document what is going on here.
 */
private[path_inference] abstract class AbstractCRF(
  obs_model: ObservationModel,
  trans_model: TransitionModel)
  extends ConditionalRandomField with MMLogging {
  /**
   * The main queue. New elements are pushed in and old elements are removed out.
   *
   * FIFO => head = oldest, last = most recent
   */
  private[this] val queue: Queue[CRFFrame] = new Queue[CRFFrame]()
  /**
   * The number of forward frames already computed.
   * This number decreases whenever a frame is pulled.
   */
  protected var num_forward: Int = 0
  /*
   * The number of backward interations already computed for this queue.
   * This number gets reset whenever a new frame is pushed.
   */
  protected var num_backward: Int = 0

  /**
   * The output of the queue.
   */
  protected val out_queue: Queue[CRFFrame] = new Queue[CRFFrame]()

  def numStoredFrames = queue.size

  /**
   * Filter will die after an internal exception.
   * No exception should be thrown anymore now.
   * The disconnected cases are handled upfront before starting the
   * computations.
   */
  val debugMode = true

  /**
   * Initialization of values
   */
  protected val init_empty_value = -1e200

  def setFirstPoint(point: ProbeCoordinate[Link]) {
    logDebug("Adding point: " + point)
    val nspots = point.spots.size
    val payload = point
    val obs_log = obs_model.logObs(point)
    assert(!obs_log.exists(_.isInfinite), (point, obs_log))

    // Connect to the previous frame by the transition matrix
    // Big chunk of functional programming ...
    if (!queue.isEmpty) {
      val delta: Delta = queue.last.payload.asInstanceOf[Delta]
      val transitions2 = CRFUtils.findTransitionsDeltaToPC2(delta, point)
      val transitions2_from = transitions2.map(_._1)
      val transitions2_to = transitions2.map(_._2)
      val old_frame = queue.last
      //      old_frame.sp_transition = transitions2
      old_frame.sp_transition_from = transitions2_from
      old_frame.sp_transition_to = transitions2_to
    }

    // Wrap the array in a vector
    val wrapper_vec = new DenseVectorCol(point.probabilities.rawData)
    val wrapper_obs_log = new DenseVectorCol(obs_log)
    // Do not touch the values of the probabilities here
    // bacause we also reinject the same point later on during finalizeation
    // This would cause the prob vecbe reset
    // This is not necessary, but allows tomonitor changes when inserting frames
    //    wrapper_vec := wrapper_obs_log

    val frame = new CRFFrame(point,
      point.time,
      wrapper_obs_log,
      DenseVectorCol.zeros[Double](nspots),
      DenseVectorCol.zeros[Double](nspots),
      wrapper_vec,
      null, null)
    this += frame
  }

  def addPair(delta: Delta, point: ProbeCoordinate[Link]): Unit = {
    logDebug("Adding delta and point")
    logDebug("Delta:\n" + delta)
    logDebug("Point:\n" + point)
    // Should never be called with an empty queue, a point should
    // always have been added first.
    assert(!queue.isEmpty)
    //empty paths => finalize computations and clear everything
    // It may happen also that the filter just got reset because of a model exception
    if (delta.paths.isEmpty) {
      logWarning("Empty delta received, resetting CRF " + this)
      finalizeComputationsAndRestart(point)
      return
    }

    //Otherwise, compute the new transition matrix and push the frame (this will
    // trigger computations)

    val npaths = delta.paths.length
    val previous_point = queue.last.payload.asInstanceOf[ProbeCoordinate[Link]]
    assert(previous_point.time <= point.time)
    assert(previous_point.time <= delta.points.head.time, "Not in good order:" + (previous_point, delta.points.head))
    assert(previous_point.time <= delta.points.last.time)
    assert(delta.points.head.time <= point.time)
    assert(delta.points.last.time <= point.time)
    val transitions2 = CRFUtils.findTransitionsPCToDelta(previous_point, delta)
    val transitions2_from = transitions2.map(_._1)
    val transitions2_to = transitions2.map(_._2)
    queue.last.sp_transition_from = transitions2_from
    queue.last.sp_transition_to = transitions2_to
    // Compute the vector of weights / probabilities of the paths
    val path_weights = delta.paths.map(trans_model.logTrans(_)).toArray
    val wrapper_trans_log = new DenseVectorCol(path_weights)

    // Wrap the array in a vector
    val wrapper_vec = new DenseVectorCol(delta.probabilities)
    // This is not necessary, but allows tomonitor changes when inserting frames
    // THis works here. Do not do it for points
    wrapper_vec := DenseVectorCol.fill[Double](npaths)(1.0)

    //Insert the new frame
    val frame = new CRFFrame(delta,
      delta.points.head.time,
      wrapper_trans_log,
      DenseVectorCol.zeros[Double](npaths),
      DenseVectorCol.zeros[Double](npaths),
      wrapper_vec,
      null, null)
    this += frame
    // Insert the next point
    this setFirstPoint point
  }

  private def +=(frame: CRFFrame): Unit = {
    queue += frame
    num_backward = 0

    try {
      perform_computations
    } catch {
      case e: InternalMathException =>
        logWarning("Tracker reset due to internal exception")
        if (debugMode)
          throw e
        //We clear the current filter
        // Note that we still keep the last frame in the filter (if a point) to respect
        // the semantics. The next frame will be an empty path frame.
        val last_payload = queue.last.payload
        queue.clear
        // If the filter died when the last frame inserted was a point, reinsert
        // it. Otherwise, it was a path and a point will follow.
        last_payload match {
          // Is there a better way to do it due to type erasure?
          case pc: ProbeCoordinate[_] => {
            this setFirstPoint pc.asInstanceOf[ProbeCoordinate[Link]]
          }
          case _ => // Do nothing
        }
        num_forward = 0
        num_backward = 0
      case e =>
        // Unknown exception, rethrowing
        throw e
    }
  }

  protected def clearQueue: Unit = { queue.clear }

  protected def emptyQueue = { queue.isEmpty }

  /**
   * Used to manually finalize the computations in the filter.
   */
  def finalizeComputationsAndRestart(point: ProbeCoordinate[Link]): Unit = {
    logDebug("CRF " + this + " attempts to hot finalize")
    finalizeComputations
    logDebug("CRF done finalizing, restarting a new track.")
    this setFirstPoint point
  }

  /**
   * Should only be used when being done with all computations.
   * If you want to continue to add things, use finalizeComputationsAndRestart.
   */
  def finalizeComputations: Unit = {
    logDebug("CRF " + this + " in track finalization")
    try {
      finalize_computations
    } catch {
      case e: InternalMathException =>
        logWarning("Tracker reset at finalization due to internal exception")
        if (debugMode)
          throw e
      case e =>
        // Unknown exception, rethrowing
        throw e
    }
    // We clear the current filter.
    // This happens in all cases
    clearQueue
    num_forward = 0
    num_backward = 0
    logDebug("CRF done finalizing")
  }

  /**
   * Returns the payload of the last frame inserted.
   */
  def lastPayload: Any =
    {
      assert(!queue.isEmpty)
      queue.last.payload
    }

  /**
   * Main function that implements the computation's strategy.
   */
  protected def perform_computations: Unit

  /**
   * Gets called when an end-of track frame is received.
   *
   * After that, the filter is cleared.
   */
  protected def finalize_computations: Unit

  /**
   * Performs forward propagation.
   */
  protected def forward: Unit = {
    if (queue.isEmpty)
      return
    // FIll the first element
    if (num_forward == 0) {
      val head = queue.head
      val n = head.forward.size
      head.forward := head.logObservations //Uniform start probability * obs
      // Normalize to get real values
      LogMath.normalize(head.forward.data, head.posterior.data)
      num_forward = 1
      /**
       * FIXME: the values could be zero here alread
       * => add an internal exception
       */
    }

    for ((prev, next) <- (queue.slice(num_forward - 1, queue.length - 1) zip queue.slice(num_forward, queue.length))) {
      // Initialize values
      next.forward := this.init_empty_value

      // Perform the transition evaluation
      // Compact (slower) code:
      //       for ((previdx, nextidx) <- prev.sp_transition) {
      //         next.forward(nextidx) = LogMath.logSumExp(prev.forward(previdx), next.forward(nextidx))
      //       }

      {
        var i = prev.sp_transition_from.size - 1
        while (i >= 0) {
          val previdx = prev.sp_transition_from(i)
          val nextidx = prev.sp_transition_to(i)
          next.forward(nextidx) = LogMath.logSumExp(prev.forward(previdx), next.forward(nextidx))
          i -= 1
        }
      }

      if (hasNaN(next.forward.data)) {
        logWarning("Found a NaN in forward data during matrix multiply:" + next.forward.data.mkString)
        logWarning("Next frame payload:" + next.payload)
        throw new InternalMathException
      }
      // Add the observations (already in log domain)
      next.forward :+= next.logObservations
      // Sanity check if any value is at least real
      if (next.forward.data.max == Double.NegativeInfinity) {
        logDebug("zero prob in forward")
        throw new InternalMathException
      }
      if (hasNaN(next.forward.data)) {
        logWarning("Found a NaN in forward data before normalization:" + next.forward.data.mkString)
        logWarning("Next frame payload:" + next.payload)
        throw new InternalMathException
      }
      // Normalize to prevent log numbers from going too far
      LogMath.logNormalizeInPlace(next.forward.data)
      // Affect final value so that forward can be used standalone
      LogMath.normalize(next.forward.data, next.posterior.data)
      if (hasNaN(next.posterior.data)) {
        logWarning("Found a NaN in posterior data:" + next.posterior.data.mkString)
        logWarning("Forward data:" + next.forward.data.mkString)
        logWarning("Next frame payload:" + next.payload)
        throw new InternalMathException
      }
    }
    num_forward = queue.length
  }

  protected def backward: Unit = {
    if (queue.isEmpty)
      return
    if (num_backward == 0) {
      val n = queue.last.backward.size
      for (j <- (0 until n)) {
        queue.last.backward(j) = 0.0
      }
      /**
       * FIXME: the values could be zero here alread
       * => add an internal exception
       * What to do with num_forward??
       */
      num_backward = 1
    }
    for ((prev, next) <- (queue.slice(num_backward - 1, queue.length - 1).reverse zip queue.slice(num_backward, queue.length).reverse)) {
      // Initialize values
      // Sanity checks everywhere.
      prev.backward := this.init_empty_value

      // Perform the transition evaluation
      // Compact code:
      //       for ((previdx, nextidx) <- prev.sp_transition) {
      //         prev.backward(previdx) = LogMath.logSumExp(next.backward(nextidx), prev.backward(previdx))
      //       }
      {
        var i = prev.sp_transition_from.size - 1
        while (i >= 0) {
          val previdx = prev.sp_transition_from(i)
          val nextidx = prev.sp_transition_to(i)
          prev.backward(previdx) = LogMath.logSumExp(next.backward(nextidx), prev.backward(previdx))
          i -= 1
        }
      }

      if (hasNaN(prev.backward.data)) {
        logWarning("Found a NaN in backward data during matrix multiply:" + prev.backward.data.mkString)
        logWarning("Prev frame payload:" + prev.payload)
        throw new InternalMathException
      }

      // Add the observations (already in log domain)
      prev.backward :+= prev.logObservations
      // Sanity check if any value is at least real
      if (prev.backward.data.max == Double.NegativeInfinity) {
        logWarning("zero prob in backward")
        throw new InternalMathException
      }
      if (hasNaN(prev.backward.data)) {
        logWarning("Found a NaN in backward data before normalization:" + prev.backward.data.mkString)
        logWarning("Prev frame payload:" + prev.payload)
        throw new InternalMathException
      }
      // Normalize in log domain to prevent lgo numbers from straying too far
      LogMath.logNormalizeInPlace(prev.backward.data)
      if (hasNaN(prev.backward.data)) {
        logWarning("Found a NaN in backward data:" + prev.backward.data.mkString)
        logWarning("Prev frame payload:" + prev.payload)
        throw new InternalMathException
      }
      // Affect final value so that forward can be used standalone
      // All the computations stay in log domain
      LogMath.normalize(prev.backward.data, prev.posterior.data)
      if (hasNaN(prev.posterior.data)) {
        logWarning("Found a NaN in posterior data:" + prev.posterior.data.mkString)
        logWarning("Backward data:" + prev.backward.data.mkString)
        logWarning("Prev frame payload:" + prev.payload)
        throw new InternalMathException
      }
    }
    num_backward = queue.length
  }

  protected def forback: Unit = {
    forward
    backward
    for (frame <- queue) {
      assert(!frame.posterior.data.isEmpty, "" + frame.payload)
      frame.posterior := frame.forward
      frame.posterior :+= frame.backward
      if (frame.posterior.data.max == Double.NegativeInfinity) {
        logDebug(" zero prob in post normalization " + frame.forward + " \n " + frame.backward)
        throw new InternalMathException
      }
      if (hasNaN(frame.posterior.data)) {
        logWarning(" NaN detected in  post normalization ")
        throw new InternalMathException
      }
      LogMath.normalizeInPlace(frame.posterior.data)
    }
  }

  protected def length: Int = queue.length

  protected def keepAtMost(n: Int): Unit = {
    while (queue.length > n) {
      queue.dequeue
      num_forward -= 1
    }
    assert(num_forward >= 0)
    assert(num_forward <= length)
  }

  protected def removeUntil(t: Time): Unit = {
    while (queue.head.time > t) {
      queue.dequeue
      num_forward -= 1
    }
  }

  protected def push_all_out: Unit = {
    for (frame <- queue) {
      if (hasNaN(frame.posterior.data)) {
        logWarning(" Found a NaN in push_ll_out, this should not happen")
      } else {
        out_queue += frame
      }
    }
  }

  protected def push_out(n: Int): Unit = {
    assert(n >= 0)
    assert(n < queue.length)
    val frame = queue(n)
    if (hasNaN(frame.posterior.data)) {
      logWarning(" Found a NaN in push_out, this should not happen")
    } else {
      logDebug("Pushing frame out")
      logDebug("Frame payload:\n" + frame.payload)
      out_queue += frame
    }
  }

  protected def push_out_last(n: Int): Unit = {
    assert(n >= 0)
    assert(n < queue.length)
    val frames = queue.take(n)
    for (frame <- frames) {
      if (hasNaN(frame.posterior.data)) {
        logWarning(" Found a NaN in push_out_last, this should not happen")
      } else {
        logDebug("Pushing last frame out")
        logDebug("Last frame payload:\n" + frame.payload)
        out_queue += frame
      }
    }
  }

  private[this] def hasNaN(x: Array[Double]): Boolean = {
    val n = x.length
    // Unrolling the loop, this check is done often.
    var i = n - 1
    while (i >= 0) {
      if (x(i) == Double.NaN) {
        return true
      }
      i -= 1
    }
    false
  }

  def outputQueue = {
    val out = out_queue.dequeueAll(_ => true)
    out
  }
}
