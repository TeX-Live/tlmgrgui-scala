// TLCockpit
// Copyright 2017 Norbert Preining
// Licensed according to GPLv3+
//
// Front end for tlmgr

package TLCockpit

import javafx.collections.ObservableList
import javafx.scene.control

import TLCockpit.ApplicationMain.getClass
import TeXLive._
// import java.io.File

import scalafx.beans.property.StringProperty
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Future, SyncVar}
import scala.concurrent.ExecutionContext.Implicits.global
// import scala.reflect.io
// import scala.reflect.io.File
import scala.util.{Failure, Success}
import scalafx.beans.property.ObjectProperty
import scalafx.geometry.{HPos, Pos, VPos, Orientation}
import scalafx.scene.Cursor
import scalafx.scene.control.Alert.AlertType
import scalafx.scene.image.{Image, ImageView}
import scalafx.scene.input.{KeyCode, KeyEvent, MouseEvent}
import scalafx.scene.paint.Color
// needed see https://github.com/scalafx/scalafx/issues/137
import scalafx.scene.control.TableColumn._
import scalafx.scene.control.TreeTableColumn._
import scalafx.scene.control.TreeItem
import scalafx.scene.control.Menu._
import scalafx.scene.control.ListCell
import scalafx.scene.control.cell._
import scalafx.scene.text.Text
import scalafx.Includes._
import scalafx.application.{JFXApp, Platform}
import scalafx.application.JFXApp.PrimaryStage
import scalafx.geometry.Insets
import scalafx.scene.Scene
import scalafx.scene.layout._
import scalafx.scene.control._
import scalafx.event.ActionEvent
import scala.sys.process._
import scalafx.collections.ObservableBuffer
import scalafx.collections.ObservableMap

import spray.json._
import TeXLive.TLPackageJsonProtocol._




// import java.awt.Desktop

// TODO when installing a collection list the additionally installed packages, too
// TODO idea pkg tree with collection -> packages
// TODO pkg info listview adjust height according to font size and # of items
// TODO TreeTableView indentation is lazy

object ApplicationMain extends JFXApp {

  val version: String = getClass.getPackage.getImplementationVersion

  // necessary action when Window is closed with X or some other operation
  override def stopApp(): Unit = {
    tlmgr.cleanup()
  }

  val iconImage = new Image(getClass.getResourceAsStream("tlcockpit-48.jpg"))
  val logoImage = new Image(getClass.getResourceAsStream("tlcockpit-128.jpg"))

  val tlpkgs: ObservableMap[String, TLPackage] = ObservableMap[String,TLPackage]()
  val pkgs: ObservableMap[String, TLPackageDisplay] = ObservableMap[String, TLPackageDisplay]()
  val upds: ObservableMap[String, TLUpdate] = ObservableMap[String, TLUpdate]()
  val bkps: ObservableMap[String, Map[String, TLBackup]] = ObservableMap[String, Map[String,TLBackup]]()  // pkgname -> (version -> TLBackup)*

  val errorText: ObservableBuffer[String] = ObservableBuffer[String]()
  val outputText: ObservableBuffer[String] = ObservableBuffer[String]()

  val outputfield: TextArea = new TextArea {
    editable = false
    wrapText = true
    text = ""
  }
  val errorfield: TextArea = new TextArea {
    editable = false
    wrapText = true
    text = ""
  }
  errorText.onChange({
    errorfield.text = errorText.mkString("\n")
    errorfield.scrollTop = Double.MaxValue
  })
  outputText.onChange({
    outputfield.text = outputText.mkString("\n")
    outputfield.scrollTop = Double.MaxValue
  })

  val update_all_menu: MenuItem = new MenuItem("Update all") {
    onAction = (ae) => callback_update("--all")
    disable = true
  }
  val update_self_menu: MenuItem = new MenuItem("Update self") {
    onAction = (ae) => callback_update("--self")
    disable = true
  }

  val outerrtabs: TabPane = new TabPane {
    minWidth = 400
    tabs = Seq(
      new Tab {
        text = "Output"
        closable = false
        content = outputfield
      },
      new Tab {
        text = "Error"
        closable = false
        content = errorfield
      }
    )
  }
  val outerrpane: TitledPane = new TitledPane {
    text = "Debug"
    collapsible = true
    expanded = false
    content = outerrtabs
  }

  val cmdline = new TextField()
  cmdline.onKeyPressed = {
    (ae: KeyEvent) => if (ae.code == KeyCode.Enter) callback_run_cmdline()
  }
  val outputLine = new SyncVar[String]

  def tlmgr_async_command(s: String, f: Array[String] => Unit): Unit = {
    errorText.clear()
    outputText.clear()
    outerrpane.expanded = false
    statusMenu.text = "Status: Busy"
    val foo = Future {
      tlmgr.send_command(s)
    }
    foo onComplete {
      case Success(ret) =>
        f(ret)
        Platform.runLater { statusMenu.text = "Status: Idle" }
      case Failure(t) =>
        errorText.append("An ERROR has occurred calling tlmgr: " + t.getMessage)
        outerrpane.expanded = true
        outerrtabs.selectionModel().select(1)
        Platform.runLater { statusMenu.text = "Status: Idle" }
    }
  }

  def callback_quit(): Unit = {
    tlmgr.cleanup()
    Platform.exit()
    sys.exit(0)
  }

  def callback_run_text(s: String): Unit = {
    tlmgr_async_command(s, (s: Array[String]) => {})
  }

  def callback_run_cmdline(): Unit = {
    tlmgr_async_command(cmdline.text.value, s => {
      outputText.appendAll(s)
      outerrpane.expanded = true
      outerrtabs.selectionModel().select(0)
    })
  }

  def not_implemented_info(): Unit = {
    new Alert(AlertType.Warning) {
      initOwner(stage)
      title = "Warning"
      headerText = "This functionality is not implemented by now!"
      contentText = "Sorry for the inconveniences."
    }.showAndWait()
  }

  val OutputBuffer: ObservableBuffer[String] = ObservableBuffer[String]()
  var OutputBufferIndex:Int = 0
  val OutputFlushLines = 100
  OutputBuffer.onChange {
    // length is number of lines!
    var foo = ""
    OutputBuffer.synchronized(
      if (OutputBuffer.length - OutputBufferIndex > OutputFlushLines) {
        foo = OutputBuffer.slice(OutputBufferIndex, OutputBufferIndex + OutputFlushLines).mkString("")
        OutputBufferIndex += OutputFlushLines
        Platform.runLater {
          outputText.append(foo)
        }
      }
    )
  }
  def reset_output_buffer(): Unit = {
    OutputBuffer.clear()
    OutputBufferIndex = 0
  }

  def callback_run_external(s: String, unbuffered: Boolean = true): Unit = {
    outputText.clear()
    errorText.clear()
    outerrpane.expanded = true
    outerrtabs.selectionModel().select(0)
    outputText.append(s"Running $s" + (if (unbuffered) " (unbuffered)" else " (buffered)"))
    val foo = Future {
      s ! ProcessLogger(
        line => if (unbuffered) Platform.runLater( outputText.append(line) )
                else OutputBuffer.synchronized( OutputBuffer.append(line + "\n") ),
        line => Platform.runLater( errorText.append(line) )
      )
    }
    foo.onComplete {
      case Success(ret) =>
        Platform.runLater {
          outputText.append(OutputBuffer.slice(OutputBufferIndex,OutputBuffer.length).mkString(""))
          outputText.append("Completed")
          reset_output_buffer()
          outputfield.scrollTop = Double.MaxValue
        }
      case Failure(t) =>
        Platform.runLater {
          outputText.append(OutputBuffer.slice(OutputBufferIndex,OutputBuffer.length).mkString(""))
          outputText.append("Completed")
          reset_output_buffer()
          outputfield.scrollTop = Double.MaxValue
          errorText.append("An ERROR has occurred running $s: " + t.getMessage)
          errorfield.scrollTop = Double.MaxValue
          outerrpane.expanded = true
          outerrtabs.selectionModel().select(1)
        }
    }
  }

  def callback_about(): Unit = {
    new Alert(AlertType.Information) {
      initOwner(stage)
      title = "About TLCockpit"
      graphic = new ImageView(logoImage)
      headerText = "TLCockpit version " + version + "\n\nManage your TeX Live with speed!"
      contentText = "Copyright 2017 Norbert Preining\nLicense: GPL3+\nSources: https://github.com/TeX-Live/tlcockpit"
    }.showAndWait()
  }

  def callback_update(s: String): Unit = {
    var prevUpdName = ""
    var prevUpdPkg  = new TLUpdate("","","","","","")
    lineUpdateFunc = (l:String) => {
      // println("line update: " + l + "=")
      l match {
        case u if u.startsWith("location-url") => None
        case u if u.startsWith("total-bytes") => None
        case u if u.startsWith("end-of-header") => None
        // case u if u.startsWith("end-of-updates") => None
        case u if u == "OK" => None
        case u if u.startsWith("tlmgr>") => None
        case u =>
          if (prevUpdName != "") {
            // println("Removing " + prevUpdName + " from list!")
            upds.remove(prevUpdName)
            val op = pkgs(prevUpdName)
            pkgs(prevUpdName) = new TLPackageDisplay(prevUpdPkg.name.value, op.rrev.value.toString, op.rrev.value.toString,
              prevUpdPkg.shortdesc.value, op.size.value.toString, "Installed")
            Platform.runLater {
              trigger_update("upds")
              trigger_update("pkgs")
            }
          }
          if (u.startsWith("end-of-updates")) {
            // nothing to be done, all has been done above
            // println("got end of updates")
          } else {
            // println("getting update line")
            val foo = parse_one_update_line(l)
            val pkgname = foo.name.value
            val PkgTreeItemOption = updateTable.root.value.children.find(_.value.value.name.value == pkgname)
            PkgTreeItemOption match {
              case Some(p) =>
                prevUpdName = pkgname
                prevUpdPkg  = p.getValue
                // println("Setting status to Updating ... for " + pkgname)
                prevUpdPkg.status = StringProperty("Updating ...")
                Platform.runLater {
                  // println("calling updateTable refresh")
                  updateTable.refresh()
                }
              case None => println("Very strange: Cannot find TreeItem for upd package" + pkgname)
            }
          }
      }
    }
    // val cmd = if (s == "--self") "update --self --no-restart" else s"update $s"
    val cmd = if (s == "--self") "update --self" else s"update $s"
    tlmgr_async_command(cmd, _ => {
      Platform.runLater {
        lineUpdateFunc = { (s: String) => }
        if (s == "--self") {
          reinitialize_tlmgr()
          // this doesn't work seemingly
          // update_upds_list()
        }
      }
    })
  }



  def do_one_pkg(what: String, pkg: String): Unit = {
    tlmgr_async_command(s"$what $pkg", _ => { Platform.runLater { update_pkgs_lists() } })
  }

  def callback_restore_pkg(str: String, rev: String): Unit = {
    not_implemented_info()
  }

  bkps.onChange( (obs,chs) => {
    var doit = chs match {
      case ObservableMap.Add(k, v) => k.toString == "root"
      case ObservableMap.Replace(k, va, vr) => k.toString == "root"
      case ObservableMap.Remove(k, v) => k.toString == "root"
    }
    if (doit) {
      // println("DEBUG bkps.onChange called new length = " + bkps.keys.toArray.length)
      val newroot = new TreeItem[TLBackup](new TLBackup("root", "", "")) {
        children = bkps
          .filter(_._1 != "root")
          .map(p => {
            val pkgname: String = p._1
            // we sort by negative of revision number, which give inverse sort
            val versmap: Array[(String, TLBackup)] = p._2.toArray.sortBy(-_._2.rev.value.toInt)

            val foo: Seq[TreeItem[TLBackup]] = versmap.tail.sortBy(-_._2.rev.value.toInt).map { q =>
              new TreeItem[TLBackup](q._2)
            }.toSeq
            new TreeItem[TLBackup](versmap.head._2) {
              children = foo
            }
          }).toArray.sortBy(_.value.value.name.value)
      }
      Platform.runLater {
        backupTable.root = newroot
      }
    }
  })
  pkgs.onChange( (obs,chs) => {
    var doit = chs match {
      case ObservableMap.Add(k, v) => k.toString == "root"
      case ObservableMap.Replace(k, va, vr) => k.toString == "root"
      case ObservableMap.Remove(k, v) => k.toString == "root"
    }
    if (doit) {
      val pkgbuf: ArrayBuffer[TLPackageDisplay] = ArrayBuffer.empty[TLPackageDisplay]
      val binbuf = scala.collection.mutable.Map.empty[String, ArrayBuffer[TLPackageDisplay]]
      pkgs.foreach(pkg => {
        // complicated part, determine whether it is a sub package or not!
        // we strip of initial texlive. prefixes to make sure we deal
        // with real packages
        if (pkg._1.stripPrefix("texlive.").contains(".")) {
          val foo: Array[String] = pkg._1.stripPrefix("texlive.infra").split('.')
          val pkgname = foo(0)
          val binname = foo(1)
          if (binbuf.keySet.contains(pkgname)) {
            binbuf(pkgname) += pkg._2
          } else {
            binbuf(pkgname) = ArrayBuffer[TLPackageDisplay](pkg._2)
          }
        } else if (pkg._1 == "root") {
          // ignore the dummy root element,
          // only used for speeding up event handling
        } else {
          pkgbuf += pkg._2
        }
      })
      // now we have all normal packages in pkgbuf, and its sub-packages in binbuf
      // we need to create TreeItems
      val viewkids: ArrayBuffer[TreeItem[TLPackageDisplay]] = pkgbuf.map { p => {
        val kids: Seq[TLPackageDisplay] = if (binbuf.keySet.contains(p.name.value)) {
          binbuf(p.name.value)
        } else {
          Seq()
        }
        // for ismixed we && all the installed status. If all are installed, we get true
        val allinstalled = (kids :+ p).foldRight[Boolean](true)((k, b) => k.installed.value == "Installed" && b)
        val someinstalled = (kids :+ p).exists(_.installed.value == "Installed")
        val mixedinstalled = !allinstalled && someinstalled
        if (mixedinstalled) {
          // replace installed status with "Mixed"
          new TreeItem[TLPackageDisplay](new TreeItem[TLPackageDisplay](
            new TLPackageDisplay(p.name.value, p.lrev.value.toString, p.rrev.value.toString, p.shortdesc.value, p.size.value.toString, "Mixed")
          )) {
            children = kids.map(new TreeItem[TLPackageDisplay](_))
          }
        } else {
          new TreeItem[TLPackageDisplay](p) {
            children = kids.map(new TreeItem[TLPackageDisplay](_))
          }
        }
      }
      }
      Platform.runLater {
        packageTable.root = new TreeItem[TLPackageDisplay](new TLPackageDisplay("root", "0", "0", "", "0", "")) {
          expanded = true
          children = viewkids.sortBy(_.value.value.name.value)
        }
      }
    }
  })
  upds.onChange( (obs, chs) => {
    var doit = chs match {
      case ObservableMap.Add(k, v) => k.toString == "root"
      case ObservableMap.Replace(k, va, vr) => k.toString == "root"
      case ObservableMap.Remove(k, v) => k.toString == "root"
    }
    if (doit) {
      val infraAvailable = upds.keys.exists(_.startsWith("texlive.infra"))
      // only allow for updates of other packages when no infra update available
      val updatesAvailable = !infraAvailable && upds.keys.exists(p => !p.startsWith("texlive.infra") && !(p == "root"))
      val newroot = new TreeItem[TLUpdate](new TLUpdate("root", "", "", "", "", "")) {
        children = upds
          .filter(_._1 != "root")
          .map(p => new TreeItem[TLUpdate](p._2))
          .toArray
          .sortBy(_.value.value.name.value)
      }
      Platform.runLater {
        update_self_menu.disable = !infraAvailable
        update_all_menu.disable = !updatesAvailable
        updateTable.root = newroot
        if (infraAvailable) {
          texlive_infra_update_warning()
        }
      }
    }
  })

  def texlive_infra_update_warning(): Unit = {
    new Alert(AlertType.Warning) {
      initOwner(stage)
      title = "TeX Live Infrastructure Update Available"
      headerText = "Updates to the TeX Live Manager (Infrastructure) available."
      contentText = "Please use \"Update self\" from the Menu!"
    }.showAndWait()
  }

  def update_bkps_list(): Unit = {
    tlmgr_async_command("restore", lines => {
      // lines.drop(1).foreach(println(_))
      val newbkps: Map[String, Map[String, TLBackup]] = lines.drop(1).map { (l: String) =>
        val fields = l.split("[ ():]", -1).filter(_.nonEmpty)
        val pkgname = fields(0)
        val rests: Array[Array[String]] = fields.drop(1).sliding(4, 4).toArray
        (pkgname, rests.map({ p => (p(1), new TLBackup(pkgname, p(0), p(1) + " " + p(2) + ":" + p(3)))}).toMap)
      }.toMap
      bkps.clear()
      bkps ++= newbkps
      trigger_update("bkps")
    })
  }
  def update_pkgs_lists():Unit = {
    tlmgr_async_command("info --data name,localrev,remoterev,shortdesc,size,installed", (s: Array[String]) => {
      val newpkgs = s.map { (line: String) =>
        val fields: Array[String] = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1)
        val sd = fields(3)
        val shortdesc = if (sd.isEmpty) "" else sd.substring(1).dropRight(1).replace("""\"""",""""""")
        val inst = if (fields(5) == "1") "Installed" else "Not installed"
        (fields(0), new TLPackageDisplay(fields(0), fields(1), fields(2), shortdesc, fields(4), inst))
      }.toMap
      pkgs.clear()
      pkgs ++= newpkgs
      trigger_update("pkgs")
    })
  }

  def update_pkgs_lists_dev():Unit = {
    tlmgr_async_command("info --data json", (s: Array[String]) => {
      val jsonAst = s.mkString("").parseJson
      tlpkgs.clear()
      tlpkgs ++= jsonAst.convertTo[List[TLPackage]].map { p => (p.name, p)}
      val newpkgs: Map[String, TLPackageDisplay] = tlpkgs.map { p =>
        (p._2.name, new TLPackageDisplay(p._2.name, p._2.lrev.toString, p._2.rrev.toString, p._2.shortdesc, "0", if (p._2.installed) "Installed" else "Not installed"))
      }.toMap
      pkgs.clear()
      pkgs ++= newpkgs
      trigger_update("pkgs")
    })
  }

  def parse_one_update_line(l: String): TLUpdate = {
    val fields = l.split("\t")
    val pkgname = fields(0)
    val status = fields(1) match {
      case "d" => "Removed on server"
      case "f" => "Forcibly removed"
      case "u" => "Update available"
      case "r" => "Local is newer"
      case "a" => "New on server"
      case "i" => "Not installed"
      case "I" => "Reinstall"
    }
    val localrev = fields(2)
    val serverrev = fields(3)
    val size = humanReadableByteSize(fields(4).toLong)
    val runtime = fields(5)
    val esttot = fields(6)
    val tag = fields(7)
    val lctanv = fields(8)
    val rctanv = fields(9)
    val tlpkg: TLPackageDisplay = pkgs(pkgname)
    val shortdesc = tlpkg.shortdesc.value
    new TLUpdate(pkgname, status,
      localrev + {
        if (lctanv != "-") s" ($lctanv)" else ""
      },
      serverrev + {
        if (rctanv != "-") s" ($rctanv)" else ""
      },
      shortdesc, size)
  }

  def update_upds_list(): Unit = {
    tlmgr_async_command("update --list", lines => {
      // val newupds = scala.collection.mutable.Map.empty[String,TLUpdate]
      val newupds: Map[String, TLUpdate] = lines.filter { l =>
        l match {
          case u if u.startsWith("location-url") => false
          case u if u.startsWith("total-bytes") => false
          case u if u.startsWith("end-of-header") => false
          case u if u.startsWith("end-of-updates") => false
          case u => true
        }
      }.map { l =>
        val foo = parse_one_update_line(l)
        (foo.name.value, foo)
      }.toMap
      val infraAvailable = newupds.keys.exists(_.startsWith("texlive.infra"))
      upds.clear()
      if (infraAvailable) {
        upds ++= Seq( ("texlive.infra", newupds("texlive.infra") ) )
      } else {
        upds ++= newupds
      }
      trigger_update("upds")
    })
  }

  def trigger_update(s:String): Unit = {
    // println("Triggering update of " + s)
    if (s == "pkgs")
      pkgs("root") = new TLPackageDisplay("root","0","0","","0","")
    else if (s == "upds")
      upds("root") = new TLUpdate("root", "", "", "", "", "")
    else if (s == "bkps")
      bkps("root") = Map[String,TLBackup](("0", new TLBackup("root","0","0")))
  }

  def doListView(files: Seq[String], clickable: Boolean): scalafx.scene.Node = {
    if (files.length <= 5) {
      val vb = new VBox()
      vb.children = files.map { f =>
        val fields = f.split(" ")
        new Label(fields(0)) {
          if (clickable) {
            textFill = Color.Blue
            onMouseClicked = { me: MouseEvent => OsTools.openFile(tlmgr.tlroot + "/" + fields(0)) }
            cursor = Cursor.Hand
          }
        }
      }
      vb
    } else {
      val vb = new ListView[String] {}
      vb.minHeight = 150
      vb.prefHeight = 150
      vb.maxHeight = 200
      vb.vgrow = Priority.Always
      // TODO tighter spacing for ListView
      vb.orientation = Orientation.Vertical
      vb.cellFactory = { p => {
        val foo = new ListCell[String]
        foo.item.onChange { (_, _, str) => foo.text = str }
        if (clickable) {
          foo.textFill = Color.Blue
          foo.onMouseClicked = { me: MouseEvent => OsTools.openFile(tlmgr.tlroot + "/" + foo.text.value) }
          foo.cursor = Cursor.Hand
        }
        foo
      }
      }
      // vb.children = docFiles.map { f =>
      vb.items = ObservableBuffer(files.map { f =>
        val fields = f.split(" ")
        fields(0)
      })
      vb
    }
  }

  def callback_show_pkg_info(pkg: String): Unit = {
    tlmgr_async_command(s"info --list $pkg", pkginfo => {
      // need to call runLater to go back to main thread!
      Platform.runLater {
        val dialog = new Dialog() {
          initOwner(stage)
          title = s"Package Information for $pkg"
          headerText = s"Package Information for $pkg"
          resizable = true
        }
        dialog.dialogPane().buttonTypes = Seq(ButtonType.OK)
        val isInstalled = pkgs(pkg).installed.value == "Installed"
        val grid = new GridPane() {
          hgap = 10
          vgap = 10
          padding = Insets(20)
        }
        var crow = 0
        val runFiles = ArrayBuffer[String]()
        val docFiles = ArrayBuffer[String]()
        val binFiles = ArrayBuffer[String]()
        val srcFiles = ArrayBuffer[String]()
        var whichFiles = "run" // we assume run files if not found!
        pkginfo.foreach((line: String) => {
          if (line.startsWith("  ")) {
            // files are listed with two spaces in the beginning
            whichFiles match {
              case "run" => runFiles += line.trim
              case "doc" => docFiles += line.trim
              case "bin" => binFiles += line.trim
              case "src" => srcFiles += line.trim
              case _ => println("Don't know what to do?!?!")
            }
          } else {
            val keyval = line.split(":", 2).map(_.trim)
            whichFiles = if (keyval(0).startsWith("run files")) "run" else
              if (keyval(0).startsWith("doc files")) "doc" else
              if (keyval(0).startsWith("source files")) "src" else
              if (keyval(0).startsWith("bin files (all platforms)")) "bin" else
              "unknown"
            if (keyval.length == 2) {
              if (keyval(0) == "doc files" || keyval(0) == "source files" || keyval(0) == "run files" ||
                  keyval(0).startsWith("depending package ") ||
                  keyval(0).startsWith("bin files (all platforms)") || keyval(0).startsWith("Included files, by type")) {
                // do nothing
              } else {
                val keylabel = new Label(keyval(0))
                val vallabel = new Label(keyval(1))
                vallabel.wrapText = true
                grid.add(keylabel, 0, crow)
                grid.add(vallabel, 1, crow)
                crow += 1
              }
            }
          }
        })
        // add files section
        if (docFiles.nonEmpty) {
          grid.add(new Label("doc files"), 0, crow)
          //grid.add(new Label(docFiles.mkString("\n")) { wrapText = true },1, crow)
          grid.add(doListView(docFiles.map(s => s.replaceFirst("RELOC","texmf-dist")),isInstalled),1,crow)
          crow += 1
        }
        if (runFiles.nonEmpty) {
          grid.add(new Label("run files"), 0, crow)
          // grid.add(new Label(runFiles.mkString("\n")) { wrapText = true },1, crow)
          grid.add(doListView(runFiles.map(s => s.replaceFirst("RELOC","texmf-dist")),false),1,crow)
          crow += 1
        }
        if (srcFiles.nonEmpty) {
          grid.add(new Label("src files"), 0, crow)
          // grid.add(new Label(srcFiles.mkString("\n")) { wrapText = true },1, crow)
          grid.add(doListView(srcFiles.map(s => s.replaceFirst("RELOC","texmf-dist")),false),1,crow)
          crow += 1
        }
        if (binFiles.nonEmpty) {
          grid.add(new Label("bin files"), 0, crow)
          // grid.add(new Label(binFiles.mkString("\n")) { wrapText = true },1, crow)
          grid.add(doListView(binFiles.map(s => s.replaceFirst("RELOC","texmf-dist")),false),1,crow)
          crow += 1
        }
        grid.columnConstraints = Seq(new ColumnConstraints(100, 200, 200), new ColumnConstraints(100, 400, 5000, Priority.Always, new HPos(HPos.Left), true))
        dialog.dialogPane().content = grid
        dialog.width = 600
        dialog.height = 1500
        dialog.showAndWait()
      }
    })
  }

  def callback_show_pkg_info_dev(pkg: String): Unit = {
    val dialog = new Dialog() {
      initOwner(stage)
      title = s"Package Information for $pkg"
      headerText = s"Package Information for $pkg"
      resizable = true
    }
    dialog.dialogPane().buttonTypes = Seq(ButtonType.OK)
    val isInstalled = tlpkgs(pkg).installed
    val grid = new GridPane() {
      hgap = 10
      vgap = 10
      padding = Insets(20)
    }
    def do_one(k: String, v: String, row: Int): Int = {
      grid.add(new Label(k), 0, row)
      grid.add(new Label(v) { wrapText = true}, 1, row)
      row + 1
    }
    var crow = 0
    val tlp = tlpkgs(pkg)
    crow = do_one("package", pkg, crow)
    crow = do_one("category", tlp.category, crow)
    crow = do_one("shortdesc", tlp.shortdesc, crow)
    crow = do_one("longdesc", tlp.longdesc, crow)
    crow = do_one("installed", if (tlp.installed) "Yes" else "No", crow)
    crow = do_one("available", if (tlp.available) "Yes" else "No", crow)
    if (tlp.installed)
      crow = do_one("local revision", tlp.lrev.toString, crow)
    if (tlp.available)
      crow = do_one("remote revision", tlp.rrev.toString, crow)
    val binsizestr = if (tlp.binsize > 0) "bin "+humanReadableByteSize(tlp.binsize)+" " else "";
    val runsizestr = if (tlp.runsize > 0) "run "+humanReadableByteSize(tlp.runsize)+" " else "";
    val srcsizestr = if (tlp.srcsize > 0) "src "+humanReadableByteSize(tlp.srcsize)+" " else "";
    val docsizestr = if (tlp.docsize > 0) "doc "+humanReadableByteSize(tlp.docsize)+" " else "";
    crow = do_one("sizes", runsizestr+docsizestr+binsizestr+srcsizestr, crow)
    val catdata = tlp.cataloguedata
    if (catdata.version != "")
      crow = do_one("cat-version", catdata.version, crow)
    if (catdata.date != "")
      crow = do_one("cat-date", catdata.date, crow)
    if (catdata.license != "")
      crow = do_one("cat-license", catdata.license, crow)
    if (catdata.topics != "")
      crow = do_one("cat-topics", catdata.topics, crow)
    if (catdata.related != "")
      crow = do_one("cat-related", catdata.related, crow)
    // add files section
    //println(tlpkgs(pkg))
    val docFiles = tlpkgs(pkg).docfiles
    if (docFiles.nonEmpty) {
      grid.add(new Label("doc files"), 0, crow)
      grid.add(doListView(docFiles.map(s => s.file.replaceFirst("RELOC", "texmf-dist")), isInstalled), 1, crow)
      crow += 1
    }
    val runFiles = tlpkgs(pkg).runfiles
    if (runFiles.nonEmpty) {
      grid.add(new Label("run files"), 0, crow)
      grid.add(doListView(runFiles.map(s => s.replaceFirst("RELOC", "texmf-dist")), false), 1, crow)
      crow += 1
    }
    val srcFiles = tlpkgs(pkg).srcfiles
    if (srcFiles.nonEmpty) {
      grid.add(new Label("src files"), 0, crow)
      grid.add(doListView(srcFiles.map(s => s.replaceFirst("RELOC", "texmf-dist")), false), 1, crow)
      crow += 1
    }
    val binFiles = tlpkgs(pkg).binfiles
    if (binFiles.nonEmpty) {
      grid.add(new Label("bin files"), 0, crow)
      grid.add(doListView(binFiles.map(s => s.replaceFirst("RELOC", "texmf-dist")), false), 1, crow)
      crow += 1
    }
    grid.columnConstraints = Seq(new ColumnConstraints(100, 200, 200), new ColumnConstraints(100, 400, 5000, Priority.Always, new HPos(HPos.Left), true))
    dialog.dialogPane().content = grid
    dialog.width = 600
    dialog.height = 1500
    dialog.showAndWait()
  }
  /**
    * @see https://stackoverflow.com/questions/3263892/format-file-size-as-mb-gb-etc
    * @see https://en.wikipedia.org/wiki/Zettabyte
    * @param fileSize Up to Exabytes
    * @return
    */
  def humanReadableByteSize(fileSize: Long): String = {
    if(fileSize <= 0) return "0 B"
    // kilo, Mega, Giga, Tera, Peta, Exa, Zetta, Yotta
    val units: Array[String] = Array("B", "kB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB")
    val digitGroup: Int = (Math.log10(fileSize)/Math.log10(1024)).toInt
    f"${fileSize/Math.pow(1024, digitGroup)}%3.1f ${units(digitGroup)}"
  }

  val mainMenu: Menu = new Menu("TLCockpit") {
    items = List(
      update_all_menu,
      update_self_menu,
      new SeparatorMenuItem,
      new MenuItem("Update filename databases ...") {
        onAction = (ae) => {
          callback_run_external("mktexlsr")
          callback_run_external("mtxrun --generate")
        }
      },
      // too many lines are quickly output -> GUI becomes hanging until
      // all the callbacks are done - call fmtutil with unbuffered = false
      new MenuItem("Rebuild all formats ...") { onAction = (ae) => callback_run_external("fmtutil --sys --all", false) },
      new MenuItem("Update font map database ...") {
        onAction = (ae) => callback_run_external("updmap --sys")
      },
      /*
      new MenuItem("Restore packages from backup ...") {
        disable = true; onAction = (ae) => not_implemented_info()
      },
      new MenuItem("Handle symlinks in system dirs ...") {
        disable = true; onAction = (ae) => not_implemented_info()
      },
      new SeparatorMenuItem,
      new MenuItem("Remove TeX Live ...") {
        disable = true; onAction = (ae) => not_implemented_info()
      },
      */
      new SeparatorMenuItem,
      // temporarily move here as we disable the Help menu
      new MenuItem("About") {
        onAction = (ae) => callback_about()
      },
      new MenuItem("Exit") {
        onAction = (ae: ActionEvent) => callback_quit()
      })
  }
  val optionsMenu: Menu = new Menu("Options") {
    items = List( new MenuItem("General ...") { disable = true; onAction = (ae) => not_implemented_info() },
      new MenuItem("Paper ...") { disable = true; onAction = (ae) => not_implemented_info() },
      new MenuItem("Platforms ...") { disable = true; onAction = (ae) => not_implemented_info() },
      new SeparatorMenuItem,
      new CheckMenuItem("Expert options") { disable = true },
      new CheckMenuItem("Enable debugging options") { disable = true },
      new CheckMenuItem("Disable auto-install of new packages") { disable = true },
      new CheckMenuItem("Disable auto-removal of server-deleted packages") { disable = true }
    )
  }
  val helpMenu: Menu = new Menu("Help") {
    items = List(
      /*
      new MenuItem("Manual") {
        disable = true; onAction = (ae) => not_implemented_info()
      },
      */
      new MenuItem("About") {
        onAction = (ae) => callback_about()
      },
    )
  }
  val statusMenu: Menu = new Menu("Status: Idle") {
    disable = true
  }
  val expertPane: TitledPane = new TitledPane {
    text = "Experts only"
    collapsible = true
    expanded = false
    content = new VBox {
      spacing = 10
      children = List(
        new HBox {
          spacing = 10
          alignment = Pos.CenterLeft
          children = List(
            new Label("tlmgr shell command:"),
            cmdline,
            new Button {
              text = "Go"
              onAction = (event: ActionEvent) => callback_run_cmdline()
            }
          )
        }
      )
    }
  }
  val updateTable: TreeTableView[TLUpdate] = {
    val colName = new TreeTableColumn[TLUpdate, String] {
      text = "Package"
      cellValueFactory = { _.value.value.value.name }
      prefWidth = 150
    }
    val colStatus = new TreeTableColumn[TLUpdate, String] {
      text = "Status"
      cellValueFactory = { _.value.value.value.status }
      prefWidth = 120
    }
    val colDesc = new TreeTableColumn[TLUpdate, String] {
      text = "Description"
      cellValueFactory = { _.value.value.value.shortdesc }
      prefWidth = 300
    }
    val colLRev = new TreeTableColumn[TLUpdate, String] {
      text = "Local rev"
      cellValueFactory = { _.value.value.value.lrev }
      prefWidth = 100
    }
    val colRRev = new TreeTableColumn[TLUpdate, String] {
      text = "Remote rev"
      cellValueFactory = { _.value.value.value.rrev }
      prefWidth = 100
    }
    val colSize = new TreeTableColumn[TLUpdate, String] {
      text = "Size"
      cellValueFactory = { _.value.value.value.size }
      prefWidth = 70
    }
    val table = new TreeTableView[TLUpdate](
      new TreeItem[TLUpdate](new TLUpdate("root","","","","","")) {
        expanded = false
      }) {
      columns ++= List(colName, colStatus, colDesc, colLRev, colRRev, colSize)
    }
    colDesc.prefWidth.bind(table.width - colName.width - colLRev.width - colRRev.width - colSize.width - colStatus. width - 15)
    table.prefHeight = 300
    table.vgrow = Priority.Always
    table.placeholder = new Label("No updates available")
    table.showRoot = false
    table.rowFactory = { _ =>
      val row = new TreeTableRow[TLUpdate] {}
      val ctm = new ContextMenu(
        new MenuItem("Info") {
          onAction = (ae) => callback_show_pkg_info_dev(row.item.value.name.value)
        },
        new MenuItem("Install") {
          // onAction = (ae) => callback_run_text("install " + row.item.value.name.value)
          onAction = (ae) => do_one_pkg("install", row.item.value.name.value)
        },
        new MenuItem("Remove") {
          // onAction = (ae) => callback_run_text("remove " + row.item.value.name.value)
          onAction = (ae) => do_one_pkg("remove", row.item.value.name.value)
        },
        new MenuItem("Update") {
          // onAction = (ae) => callback_run_text("update " + row.item.value.name.value)
          onAction = (ae) => callback_update(row.item.value.name.value)
        }
      )
      row.contextMenu = ctm
      row
    }
    table
  }
  val packageTable: TreeTableView[TLPackageDisplay] = {
    val colName = new TreeTableColumn[TLPackageDisplay, String] {
      text = "Package"
      cellValueFactory = {  _.value.value.value.name }
      prefWidth = 150
    }
    val colDesc = new TreeTableColumn[TLPackageDisplay, String] {
      text = "Description"
      cellValueFactory = { _.value.value.value.shortdesc }
      prefWidth = 300
    }
    val colInst = new TreeTableColumn[TLPackageDisplay, String] {
      text = "Installed"
      cellValueFactory = { _.value.value.value.installed  }
      prefWidth = 100
    }
    val table = new TreeTableView[TLPackageDisplay](
      new TreeItem[TLPackageDisplay](new TLPackageDisplay("root","0","0","","0","")) {
        expanded = false
      }) {
      columns ++= List(colName, colDesc, colInst)
    }
    colDesc.prefWidth.bind(table.width - colInst.width - colName.width - 15)
    table.prefHeight = 300
    table.showRoot = false
    table.vgrow = Priority.Always
    table.rowFactory = { _ =>
      val row = new TreeTableRow[TLPackageDisplay] {}
      val ctm = new ContextMenu(
        new MenuItem("Info") {
          onAction = (ae) => callback_show_pkg_info_dev(row.item.value.name.value)
        },
        new MenuItem("Install") {
          onAction = (ae) => do_one_pkg("install", row.item.value.name.value)
        },
        new MenuItem("Remove") {
          onAction = (ae) => do_one_pkg("remove", row.item.value.name.value)
        }
      )
      row.contextMenu = ctm
      row
    }
    table
  }
  val backupTable: TreeTableView[TLBackup] = {
    val colName = new TreeTableColumn[TLBackup, String] {
      text = "Package"
      cellValueFactory = {  _.value.value.value.name }
      prefWidth = 150
    }
    val colRev = new TreeTableColumn[TLBackup, String] {
      text = "Revision"
      cellValueFactory = { _.value.value.value.rev }
      prefWidth = 100
    }
    val colDate = new TreeTableColumn[TLBackup, String] {
      text = "Date"
      cellValueFactory = { _.value.value.value.date }
      prefWidth = 300
    }
    val table = new TreeTableView[TLBackup](
      new TreeItem[TLBackup](new TLBackup("root","","")) {
        expanded = false
      }) {
      columns ++= List(colName, colRev, colDate)
    }
    colDate.prefWidth.bind(table.width - colRev.width - colName.width - 15)
    table.prefHeight = 300
    table.showRoot = false
    table.vgrow = Priority.Always
    table.rowFactory = { _ =>
      val row = new TreeTableRow[TLBackup] {}
      val ctm = new ContextMenu(
        new MenuItem("Info") {
          onAction = (ae) => callback_show_pkg_info_dev(row.item.value.name.value)
        },
        new MenuItem("Restore") {
          onAction = (ae) => callback_restore_pkg(row.item.value.name.value, row.item.value.rev.value)
        }
      )
      row.contextMenu = ctm
      row
    }
    table
  }
  val pkgstabs: TabPane = new TabPane {
    minWidth = 400
    vgrow = Priority.Always
    tabs = Seq(
      new Tab {
        text = "Packages"
        closable = false
        content = packageTable
      },
      new Tab {
        text = "Updates"
        closable = false
        content = updateTable
      },
      new Tab {
        text = "Backups"
        closable = false
        content = backupTable
      }
    )
  }
  pkgstabs.selectionModel().selectedItem.onChange(
    (a,b,c) => {
      if (a.value.text() == "Backups") {
        if (backupTable.root.value.children.length == 0)
          update_bkps_list()
      } else if (a.value.text() == "Updates") {
        // only update if not done already
        if (updateTable.root.value.children.length == 0)
          update_upds_list()
      }
    }
  )
  val menuBar: MenuBar = new MenuBar {
    useSystemMenuBar = true
    // menus.addAll(mainMenu, optionsMenu, helpMenu)
    menus.addAll(mainMenu, statusMenu)
  }

  stage = new PrimaryStage {
    title = "TLCockpit"
    scene = new Scene {
      root = {
        // val topBox = new HBox {
        //   children = List(menuBar, statusLabel)
        // }
        // topBox.hgrow = Priority.Always
        // topBox.maxWidth = Double.MaxValue
        val topBox = menuBar
        val centerBox = new VBox {
          padding = Insets(10)
          children = List(pkgstabs, expertPane, outerrpane)
        }
        new BorderPane {
          // padding = Insets(20)
          top = topBox
          // left = leftBox
          center = centerBox
          // bottom = bottomBox
        }
      }
    }
    icons.add(iconImage)
  }



  def initialize_tlmgr(): TlmgrProcess = {
    val tt = new TlmgrProcess(
      // (s:String) => outputfield.text = s,
      (s: Array[String]) => {
        // we don't wont normal output to be displayed
        // as it is anyway returned
        // outputText.clear()
        // outputText.appendAll(s)
      },
      (s: String) => {
        // errorText.clear()
        errorText.append(s)
        if (s.trim != "") {
          outerrpane.expanded = true
          outerrtabs.selectionModel().select(1)
        }
      },
      (s: String) => outputLine.put(s)
    )
    val bar = Future {
      while (true) {
        val s = outputLine.take
        lineUpdateFunc(s)
      }
    }
    bar.onComplete {
      case Success(value) => println(s"Got the callback, meaning = $value")
      case Failure(e) =>
        println("lineUpdateFunc thread got interrupted -- probably old tlmgr, ignoring it!")
        //e.printStackTrace
    }
    tt
  }

  def reinitialize_tlmgr(): Unit = {
    tlmgr.cleanup()
    // Thread.sleep(1000)
    tlmgr = initialize_tlmgr()
    tlmgr_post_init()
    pkgstabs.getSelectionModel().select(0)
  }

  def tlmgr_post_init():Unit = {
    tlmgr.start_process()
    tlmgr.get_output_till_prompt()
    // update_pkg_lists_to_be_renamed() // this is async done
    pkgs.clear()
    upds.clear()
    bkps.clear()
    update_pkgs_lists_dev()
  }


  /* TODO implement splash screen - see example in ProScalaFX
  val startalert = new Alert(AlertType.Information)
  startalert.setTitle("Loading package database ...")
  startalert.setContentText("Loading package database, this might take a while. Please wait!")
  startalert.show()
  */

  /*
  var testmode = false
  if (parameters.unnamed.nonEmpty) {
    if (parameters.unnamed.head == "-test" || parameters.unnamed.head == "--test") {
      println("Testing mode enabled, not actually calling tlmgr!")
      testmode = true
    }
  }
  */

  var lineUpdateFunc: String => Unit = { (l: String) => } // println(s"DEBUG: got ==$l== from tlmgr") }
  var tlmgr = initialize_tlmgr()
  tlmgr_post_init()

}  // object ApplicationMain

// vim:set tabstop=2 expandtab : //
