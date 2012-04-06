package lila
package lobby

import db.MessageRepo

import akka.actor._

import play.api.libs.json._
import play.api.libs.iteratee._

final class Hub(messageRepo: MessageRepo, history: History) extends Actor {

  private var members = Map.empty[String, Member]

  def receive = {

    case Join(uid, version, hookOwnerId) ⇒ {
      // Create an Enumerator to write to this socket
      //val channel = Enumerator.imperative[JsValue]()
      val messages = history since version
      val channel = new LilaEnumerator[JsValue](messages)
      members = members + (uid -> Member(channel, hookOwnerId))
      sender ! Connected(channel)
    }

    case Talk(txt, u) ⇒ messageRepo.add(txt, u).foreach { save ⇒
      val message = save.unsafePerformIO
      notifyAll("talk", Seq(
        "txt" -> JsString(message.text),
        "u" -> JsString(message.username)
      ))
    }

    case Entry(entry) ⇒ notifyAll("entry", JsString(entry.render))

    case AddHook(hook) ⇒ notifyAll("hook_add", Seq(
      "id" -> JsString(hook.id),
      "username" -> JsString(hook.username),
      "elo" -> hook.elo.fold(JsNumber(_), JsNull),
      "mode" -> JsString(hook.realMode.toString),
      "variant" -> JsString(hook.realVariant.toString),
      "color" -> JsString(hook.color),
      "clock" -> JsString(hook.clockOrUnlimited),
      "emin" -> hook.eloMin.fold(JsNumber(_), JsNull),
      "emax" -> hook.eloMax.fold(JsNumber(_), JsNull),
      "action" -> JsString("join"),
      "engine" -> JsBoolean(hook.engine))
    )

    case RemoveHook(hook) ⇒ notifyAll("hook_remove", JsString(hook.id))

    case BiteHook(hook, game) ⇒ notifyMember(
      "redirect", JsString(game fullIdOf game.creatorColor)
    ) _ |> { fn ⇒
        members.values filter (_ ownsHook hook) foreach fn
      }

    case Quit(uid) ⇒ { members = members - uid }
  }

  def notifyMember(t: String, data: JsValue)(member: Member) {
    val msg = JsObject(Seq("t" -> JsString(t), "d" -> data))
    member.channel push msg
  }

  def notifyAll(t: String, data: JsValue) {
    val msg = JsObject(Seq("t" -> JsString(t), "d" -> data))
    val vmsg = history += msg
    members.values.foreach(_.channel push vmsg)
  }
  def notifyAll(t: String, data: Seq[(String, JsValue)]) {
    notifyAll(t, JsObject(data))
  }
}
