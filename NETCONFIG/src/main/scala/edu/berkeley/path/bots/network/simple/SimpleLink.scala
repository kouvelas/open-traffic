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
package edu.berkeley.path.bots.network.simple

import scala.reflect.BeanProperty
import edu.berkeley.path.bots.netconfig.Link
import edu.berkeley.path.bots.core.{ GeoMultiLine, Coordinate }
import collection.JavaConversions._
import com.google.common.collect.ImmutableList

class SimpleLink(
  start_node: SimpleNode,
  end_node: SimpleNode,
  _id: Int,
  private[this] val net: SimpleNetwork) extends Link {

  @BeanProperty val startNode = start_node

  @BeanProperty val endNode = end_node

  lazy val length = start_node.coordinate.distanceCartesianInMeters(end_node.coordinate)

  val id = _id

  lazy val inLinks: ImmutableList[Link] =
    ImmutableList.copyOf(net.inLinks(start_node.id).map(net.links(_).asInstanceOf[Link]))

  lazy val outLinks: ImmutableList[Link] =
    ImmutableList.copyOf(net.outLinks(end_node.id).map(net.links(_).asInstanceOf[Link]))

  override def numLanesAtOffset(off: Double): Short = 1

  override def geoMultiLine: GeoMultiLine = {
    val c1: Coordinate = start_node.coordinate
    val c2: Coordinate = end_node.coordinate
    new GeoMultiLine(Array(c1, c2))
  }

  override def toString: String = "Link[" + id + "]"
}
