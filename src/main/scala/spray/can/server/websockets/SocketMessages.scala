package spray.can.server.websockets

import model.Frame
import spray.io.{Command, Event}

/**
 * Wraps a frame in an Event going up the pipeline
 */
case class FrameEvent(f: Frame) extends Event

/**
 * Wraps a frame in a Command going down the pipeline
 */
case class FrameCommand(frame: Frame) extends Command

/**
 * Tells the SocketServer to take this HTTP connection and swap it
 * out into a websocket connection.
 *
 * @param data a piece of data that can be given to the SocketServer
 *             when telling it to upgrade. This will be used by the
 *             SocketServer's frameHandler to create/find an actor
 *             that will handle the subsequent websocket frames
 */
case class Upgrade(data: Any) extends Command

case object WebsocketConnected extends Event