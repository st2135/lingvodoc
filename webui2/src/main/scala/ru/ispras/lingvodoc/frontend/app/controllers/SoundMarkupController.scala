package ru.ispras.lingvodoc.frontend.app.controllers

import org.scalajs.dom
import ru.ispras.lingvodoc.frontend.app.controllers.soundmarkupviewdata.{TierJS, ELANDocumentJS}
import ru.ispras.lingvodoc.frontend.extras.elan.annotation.IAnnotation
import ru.ispras.lingvodoc.frontend.extras.elan.tier.ITier
import ru.ispras.lingvodoc.frontend.app.services.{ModalOptions, ModalInstance, ModalService, BackendService}
import com.greencatsoft.angularjs.core.{Timeout, Scope}
import com.greencatsoft.angularjs.{Angular, AbstractController, injectable}
import ru.ispras.lingvodoc.frontend.app.model.{Perspective, Language, Dictionary}
import ru.ispras.lingvodoc.frontend.extras.facades.{MenuOption, BootstrapContextMenu, WaveSurfer, WaveSurferOpts}
import ru.ispras.lingvodoc.frontend.extras.elan.{Utils, ELANPArserException, ELANDocumentJquery}
import org.scalajs.dom.{EventTarget, console}
import org.singlespaced.d3js.{Selection, d3}
import org.scalajs.jquery._
import ru.ispras.lingvodoc.frontend.app.utils.LingvodocExecutionContext.Implicits.executionContext
import scala.scalajs.js
import js.JSConverters._

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExport

@js.native
trait SoundMarkupScope extends Scope {
  var ruler: Double = js.native // coordinate of wavesurfer ruler
  var elan: ELANDocumentJquery = js.native // elan document itself
  var elanJS: js.Dynamic = js.native
  var ws: WaveSurfer = js.native // for debugging, remove later

  var tierWidth: Int = js.native // displayed tier width in pixels
  var tiersNameWidth: Int = js.native // column with tier names width in pixels
  var fullWSWidth: Double = js.native // full width of displayed wavesurfer canvas
  var wsHeight: Int = js.native // height of wavesurfer
  var tierMenuOptions: js.Array[js.Any] = js.native
  var annotMenuOptions: js.Array[js.Any] = js.native
}

@injectable("SoundMarkupController")
class SoundMarkupController(scope: SoundMarkupScope,
                            timeout: Timeout,
                            instance: ModalInstance[Unit],
                            modal: ModalService,
                            backend: BackendService,
                            params: js.Dictionary[js.Function0[js.Any]])
  extends AbstractController[SoundMarkupScope](scope) {
  scope.elanJS = js.Dynamic.global.elanDoc

  scope.tierWidth = 50
  scope.tiersNameWidth = 140

  var waveSurfer: Option[WaveSurfer] = None // WS object
  private var _pxPerSec = 50 // minimum pxls per second, all timing is bounded to it
  val pxPerSecStep = 30 // zooming step
  // zoom in/out step; fake value to avoid division by zero; on ws load, it will be set correctly
  private var _duration: Double = 42.0
  scope.fullWSWidth = 0.0 // again, will be known after audio load
  scope.wsHeight = 128

  var soundMarkup: Option[String] = None
  val soundAddress = params.get("soundAddress").map(_.toString)
  val markupAddress = params.get("markupAddress").map(_.toString)

  val dictionaryClientId = params.get("dictionaryClientId").map(_.toString.toInt)
  val dictionaryObjectId = params.get("dictionaryObjectId").map(_.toString.toInt)

  // listen to svg mouseover while true, ignore it when false
  var svgIsMouseDown = false
  // true after first drag event, false after dragend
  var isDragging = false
  // true when we move right border of selection rectangle, false when left
  var rightBorderIsMoving = true
  // true when ws finished loading
  var isWSReady = false
  // When wsSeek is called because user clicked on it, we must manually call apply, because it is not ng-click.
  // However, in ome other cases, e.g. when user clicks on svg and then we update WS ruler, apply must not be called --
  // set this flag to false in such situations.
  var isWSNeedsToForceAngularRefresh = true

  // d3 selection rectangle element
  var selectionRectangle: Option[Selection[EventTarget]] = None

  //  (dictionaryClientId, dictionaryObjectId).zipped.foreach((dictionaryClientId, dictionaryObjectId) => {
  //    backend.getSoundMarkup(dictionaryClientId, dictionaryObjectId) onSuccess {
  //      case markup => parseMarkup(markup)
  //    }
  //  })
  parseMarkup(markupAddress.get)

  setMenuOptions()

  // add scope to window for debugging
  dom.window.asInstanceOf[js.Dynamic].myScope = scope

  // merge viewDataDiff into scope's elanJS
  def updateVD(viewDataDiff: js.Dynamic): Unit = {
    jQuery.extend(true, scope.elanJS, viewDataDiff)
  }

  def pxPerSec = _pxPerSec

  def pxPerSec_=(mpps: Int) = {
    _pxPerSec = mpps
    val viewDataDiff = scope.elan.setPxPerSec(pxPerSec)
    updateVD(viewDataDiff)
    isWSNeedsToForceAngularRefresh = false
    waveSurfer.foreach(_.zoom(mpps))
    updateFullWSWidth()
    syncRulersFromWS()
  }

  def duration = _duration

  def duration_=(dur: Double) = {
    _duration = dur
    updateFullWSWidth()
  }

  def updateFullWSWidth() = {
    scope.fullWSWidth = pxPerSec * duration
  }

  def setMenuOptions() = {
    val tierMenuOptions = new BootstrapContextMenu().addOpt(
      MenuOption("New Annotation Here",  newAnnotationHere _, Some({isNewAnnotationAllowedHere _}: js.Function1[js.Dynamic, Boolean]))
    )
    scope.tierMenuOptions = tierMenuOptions.toJS
    scope.annotMenuOptions = tierMenuOptions.addOpt(
      MenuOption("Edit Annotation Value", editAnnotationValue _)
    ).toJS
  }

  /**
    * Loading process requires a bit of explanation.
    * 1) We can't create wavesurfer instance until the view is loaded, because WS will not find it's div element
    * otherwise
    * 2) On the other hand, we can't fully render template without WS instance because we need sound's duration to
    * calculate right distances.
    * 4) In principle, we can start loading eaf file before or after view is loaded, however real scope.elan will not
    *   be available to view in either case, because eaf query is async, at least right now, with dummy jQuery get.
    * 5) So, loading goes like this:
    *   a) Get EAF query is sent;
    *   b) View is loaded with dummy distances, WS width is not known at the moment, real scope.elan doesn't exists --
    *      a dummy stub is used instead.
    *   c) createWaveSurfer is called, it creates WS instance and binds WS `ready` event to wsReady method.
    *   d) After that, Angular reloads the view, showing WaveSurfer box and tiers/annotations;
    *      however, distances are still dummy, because sound duration is not known
    *   e) When sound is fully loaded, wsReady is triggered and executed. Angular is forced to update the view, and final
    */

  // hack to initialize controller after loading the view
  // see http://stackoverflow.com/questions/21715256/angularjs-event-to-call-after-content-is-loaded
  @JSExport
  def createWaveSurfer(): Unit = {
    if (waveSurfer.isEmpty) {
      // params should be synchronized with sm-ruler css
      val wso = WaveSurferOpts("#waveform", waveColor = "violet", progressColor = "purple",
        cursorWidth = 1, cursorColor = "red",
        fillParent = false, minPxPerSec = pxPerSec, scrollParent = false,
        height = scope.wsHeight)
      waveSurfer = Some(WaveSurfer.create(wso))
      (waveSurfer, soundAddress).zipped.foreach((ws, sa) => {
        ws.load(sa)
      })
      waveSurfer.foreach(_.on("seek", onWSSeek _)) // bind seek event
      waveSurfer.foreach(_.on("audioprocess", onWSPlaying _)) // bind playing event
      waveSurfer.foreach(_.on("ready", wsReady _)) // bind playing event
      scope.ws = waveSurfer.get
      selectionRectangle = Some(d3.select("#selectionRect"))
    } // do not write anything here, outside if!
  }

  // called when audio is loaded and WS object is ready
  def wsReady(event: js.Dynamic): Unit = {
    console.log("ws ready!")
    isWSReady = true
    duration = getDuration
    updateFullWSWidth()
    scope.$apply({})
  }


  def parseMarkup(markupAddress: String): Unit = {
    scope.elan = ELANDocumentJquery.getDummy // to avoid errors while it is loading
    val action = (data: js.Dynamic, textStatus: String, jqXHR: js.Dynamic) => {
      val test_markup = data.toString
      try {
        scope.elan = ELANDocumentJquery(test_markup, pxPerSec)
        scope.elanJS = scope.elan.toJS
        // TODO: apply() here? if markup will be loaded later than sound
//        console.log(scope.elan.toString)
      } catch {
        case e: Exception =>
          console.error(e.getStackTrace.mkString("\n"))
          throw e
      }
      scope.ruler = 0
    }

    jQuery.get(markupAddress, success = action, dataType = "text")
  }

  @JSExport // TODO removeme
  def getDuration = { if (isWSReady) waveSurfer.get.getDuration() else 42.0 }

  @JSExport // TODO removeme
  def getCurrentTime = { if (isWSReady) waveSurfer.get.getCurrentTime() else 42.0 }

  /**
    * We have several metrics fully characterizing point in time:
    * 1) Offset in pxs from svg left border
    * 2) Progress in [0..1] of full sound duration
    * 3) Time in seconds, double
    * 4) Time in milliseconds, Long or String, if called from JS
    */

  def offsetToProgress(offset: Double) = offset / scope.fullWSWidth

  def progressToOffset(progress: Double) = progress * scope.fullWSWidth

  def offsetToSec(offset: Double) = offset / pxPerSec

  // sync rulers on wavesurfer's ruler position
  def syncRulersFromWS(forceApply: Boolean = false, applyTimeout: Boolean = false) = {
    val progress = waveSurfer.map(ws => ws.getCurrentTime() / duration)
    progress.foreach(p => setRulerProgress(p, forceApply = forceApply, applyTimeout = applyTimeout))
  }


  // set wavesurfer & svg rulers to @offset pixels from start
  def svgSeek(offset: Double): Unit = {
    // no need to call setRulerOffset here; it will be called automatically because WS will invoke wsSeek itself.
    isWSNeedsToForceAngularRefresh = false // ng-click will call apply
    val progress = offsetToProgress(offset)
    waveSurfer.foreach(_.seekTo(progress))
  }

  def setRulerProgress(progress: Double, forceApply: Boolean = false, applyTimeout: Boolean = false): Unit =
    setRulerOffset(progressToOffset(progress), forceApply, applyTimeout)

  def setRulerOffset(offset: Double, forceApply: Boolean = false, applyTimeout: Boolean = false): Unit = {
    val action = () => { scope.ruler = offset }
    if (applyTimeout)
      timeout(action)
    else if (forceApply)
      scope.$apply({
        action()
      })
    else
      action()
  }

  @JSExport
  def playPause() = waveSurfer.foreach(_.playPause())

  @JSExport
  def play(start: Int, end: Int) = waveSurfer.foreach(_.play(start, end))

  @JSExport
  def zoomIn(): Unit = { pxPerSec += pxPerSecStep; }

  @JSExport
  def zoomOut(): Unit = { pxPerSec -= pxPerSecStep; }

  @JSExport
  def save(): Unit = {
    instance.close(())
  }

  @JSExport
  def cancel(): Unit = {
    instance.close(())
  }

  def onWSSeek(progress: Double): Unit = {
    setRulerProgress(progress, forceApply = isWSNeedsToForceAngularRefresh)
    isWSNeedsToForceAngularRefresh = true
  }

  def onWSPlaying(): Unit = { syncRulersFromWS(applyTimeout = true) }

  // called when user clicks on svg, sets ruler to this place
  @JSExport
  def onSVGSeek(event: js.Dynamic): Unit = {
    console.log("svg seeking")
    svgSeek(event.offsetX.asInstanceOf[Double])
  }

  // called on svg mouse down, prepares for dragging
  @JSExport
  def onSVGMouseDown(event: js.Dynamic): Unit = {
    if (event.which.asInstanceOf[Int] == 1) {
      console.log("svg mouse down")
      svgIsMouseDown = true
      isDragging = false
    }
  }

  @JSExport
  // called on svg mouse up, finished dragging
  def onSVGMouseUp(event: js.Dynamic): Unit = {
    console.log("svg mouse up")
    svgIsMouseDown = false
  }

  @JSExport
  // called on svg mouse moving and extends/shrinks the selection rectangle if mouse down event happened earlier
  def onSVGMouseMove(event: js.Dynamic): Unit = {
    if (!svgIsMouseDown)
      return

//    console.log(s"mouse moving at offset ${event.offsetX}")
    val cursorX = Math.min(scope.fullWSWidth, Math.max(0, event.offsetX.toString.toDouble))
    if (!isDragging) { // executed on first mouse move event
      selectionRectangle.foreach(_.attr("x", cursorX).attr("width", 0))
      isDragging = true
    }
    else { // executed on every subsequent mouse move event
      val oldX = getSelectionRectangleLeftBorderOffset
      val oldWidth = getSelectionRectangleWidthOffset

      if ((rightBorderIsMoving && cursorX > oldX) ||
          (!rightBorderIsMoving && cursorX >= oldX + oldWidth)) {
        if (!rightBorderIsMoving) // first event with right border moving, just after changing left to right
          selectionRectangle.foreach(_.attr("x", oldX + oldWidth).attr("width", cursorX - oldX - oldWidth))
        else // right border is still moving
          selectionRectangle.foreach(_.attr("width", cursorX - oldX))
        rightBorderIsMoving = true
      }
      else {
        if (rightBorderIsMoving) // first event after right -> left border moving
          selectionRectangle.foreach(_.attr("x", cursorX).attr("width", oldX - cursorX))
        else // left border is still moving
          selectionRectangle.foreach(_.attr("x", cursorX).attr("width", oldX + oldWidth - cursorX))
        rightBorderIsMoving = false
      }

      svgSeek(cursorX)
    }
  }

  def getSelectionRectangleLeftBorderOffset = selectionRectangle.map(_.attr("x").toString.toDouble).getOrElse(0.0)
  def getSelectionRectangleWidthOffset = selectionRectangle.map(_.attr("width").toString.toDouble).getOrElse(0.0)

  def getSelectionRectangleLeftBorderMillis = Utils.sec2Millis(offsetToSec(getSelectionRectangleLeftBorderOffset))
  def getSelectionRectangleRightBorderMillis = Utils.sec2Millis(offsetToSec(
    getSelectionRectangleLeftBorderOffset + getSelectionRectangleWidthOffset)
  )

  @JSExport // TODO removeme
  def leftBorderMillis = getSelectionRectangleLeftBorderMillis.toString
  @JSExport // TODO removeme
  def rightBorderMillis = getSelectionRectangleRightBorderMillis.toString

  // These functions used context menu options
  def isNewAnnotationAllowedHere(itemScope: js.Dynamic): Boolean = {
    console.log("isNewAnnotationAllowedHere called")
    true
  }

  def newAnnotationHere(itemScope: js.Dynamic): Unit = {
    console.log(s"creating new annotation on tier ${itemScope.tier.asInstanceOf[ITier[IAnnotation]].getID}")
    console.log("CALLLED")
  }

  def editAnnotationValue(itemScope: js.Dynamic): Unit = {
    val annot = itemScope.annot.asInstanceOf[IAnnotation]
    val isValid: String => (Boolean, Option[String]) = (newValue: String) => {
      if (newValue == "грузите")
        (true, None)
      else
        (false, Some("Invalid value!"))
    }
    console.log(s"editing annotation value on annot ${annot.getID}")
    val options = ModalOptions()
    options.templateUrl = "/static/templates/modal/editTextField.html"
    options.controller = "EditTextFieldController"
    options.backdrop = false
    options.keyboard = false
    options.size = "sm"
    options.resolve = js.Dynamic.literal(
      params = () => {
        js.Dynamic.literal(originalValue = annot.text.asInstanceOf[js.Object],
                           invitation = "Enter Annotation Value".asInstanceOf[js.Object],
                           isValid = isValid.asInstanceOf[js.Object])
      }
    ).asInstanceOf[js.Dictionary[js.Any]]

    val instance = modal.open[String](options)
    instance.result map {
      case newVal: String => console.log(s"new value for annotation ${annot.getID} is $newVal")
    }
  }
}