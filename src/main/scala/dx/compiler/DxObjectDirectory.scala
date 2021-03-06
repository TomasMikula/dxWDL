/** Efficient lookup for dx:applets in a platform directory
  */
package dx.compiler

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}

import dx.api._
import spray.json._

// Keep all the information about an applet in packaged form
case class DxObjectInfo(name: String,
                        crDate: LocalDateTime,
                        dxObj: DxDataObject,
                        digest: Option[String]) {
  lazy val dxClass: String =
    dxObj.getClass.getSimpleName match {
      case "DxWorkflow" => "Workflow"
      case "DxApplet"   => "Applet"
      case other        => other
    }
}

// Take a snapshot of the platform target path before the build starts.
// Make an efficient directory of all the applets that exist there. Update
// the directory when an applet is compiled.
case class DxObjectDirectory(ns: IR.Bundle,
                             dxProject: DxProject,
                             folder: String,
                             projectWideReuse: Boolean,
                             dxApi: DxApi) {
  // a list of all dx:workflow and dx:applet names used in this WDL workflow
  private val allExecutableNames: Set[String] = ns.allCallables.keys.toSet

  // A map from an applet/workflow that is part of the namespace to its dx:object
  // on the target path (project/folder)
  private var objDir: Map[String, Vector[DxObjectInfo]] = bulkLookup()

  // A map from checksum to dx:executable, across the entire
  // project.  It allows reusing dx:executables across the entire
  // project, at the cost of a potentially expensive API call. It is
  // not clear this is useful to the majority of users, so it is
  // gated by the [projectWideReuse] flag.
  private val projectWideExecutableDir: Map[String, Vector[(DxDataObject, DxObjectDescribe)]] =
    if (projectWideReuse) projectBulkLookup()
    else Map.empty

  private var folders: Set[String] = Set.empty

  // Instead of looking up applets/workflows one by one, perform a bulk lookup, and
  // find all the objects in the target directory. Setup an easy to
  // use map with information on each name.
  //
  // findDataObjects can be an expensive call, both on the server and client sides.
  // We limit it by filtering on the CHECKSUM property, which is attached only to generated
  // applets and workflows. This runs the risk of missing cases where an applet name is already in
  // use by a regular dnanexus applet/workflow.
  private def bulkLookup(): Map[String, Vector[DxObjectInfo]] = {
    // find applets
    val t0 = System.nanoTime()
    val dxAppletsInFolder: Map[DxDataObject, DxObjectDescribe] =
      DxFindDataObjects(dxApi, None)
        .apply(
            Some(dxProject),
            Some(folder),
            recurse = false,
            Some("applet"),
            Vector(CHECKSUM_PROP),
            allExecutableNames.toVector,
            withInputOutputSpec = false,
            Vector.empty,
            Set.empty
        )
    val t1 = System.nanoTime()
    var diffMSec = (t1 - t0) / (1000 * 1000)
    dxApi.logger.trace(
        s"""|Found ${dxAppletsInFolder.size} applets
            |in ${dxProject.getId} folder=${folder} (${diffMSec} millisec)""".stripMargin
          .replaceAll("\n", " ")
    )

    // find workflows
    val t2 = System.nanoTime()
    val dxWorkflowsInFolder: Map[DxDataObject, DxObjectDescribe] =
      DxFindDataObjects(dxApi, None)
        .apply(
            Some(dxProject),
            Some(folder),
            recurse = false,
            Some("workflow"),
            Vector(CHECKSUM_PROP),
            allExecutableNames.toVector,
            withInputOutputSpec = false,
            Vector.empty,
            Set.empty
        )
    val t3 = System.nanoTime()
    diffMSec = (t3 - t2) / (1000 * 1000)
    dxApi.logger.trace(
        s"""|Found ${dxWorkflowsInFolder.size} workflows
            | in ${dxProject.getId} folder=${folder} (${diffMSec} millisec)""".stripMargin
          .replaceAll("\n", " ")
    )

    val dxObjects = dxAppletsInFolder ++ dxWorkflowsInFolder

    val dxObjectVec: Vector[DxObjectInfo] = dxObjects.map {
      case (dxObj, desc) =>
        val creationDate = new java.util.Date(desc.created)
        val crLdt: LocalDateTime =
          LocalDateTime.ofInstant(creationDate.toInstant, ZoneId.systemDefault())
        val chksum = desc.properties.flatMap { p =>
          p.get(CHECKSUM_PROP)
        }
        DxObjectInfo(desc.name, crLdt, dxObj, chksum)
    }.toVector

    // There could be multiple versions of the same applet/workflow, collect their
    // information in vectors
    dxObjectVec.foldLeft(Map.empty[String, Vector[DxObjectInfo]]) {
      case (accu, dxObjInfo) =>
        val name = dxObjInfo.name
        accu.get(name) match {
          case None =>
            // first time we have seen this dx:object
            accu + (name -> Vector(dxObjInfo))
          case Some(_) =>
            // there is already at least one dx:object by this name
            accu + (name -> (accu(name) :+ dxObjInfo))
        }
    }
  }

  // Scan the entire project for dx:workflows and dx:applets that we
  // already created, and may be reused, instead of recompiling.
  //
  // Note: This could be expensive, and the maximal number of replies
  // is (by default) 1000. Since the index is limited, we may miss
  // miss matches when we search. The cost would be creating a
  // dx:executable again, which is acceptable.
  //
  // findDataObjects can be an expensive call, both on the server and client sides.
  // We limit it by filtering on the CHECKSUM property, which is attached only to generated
  // applets and workflows.
  private def projectBulkLookup(): Map[String, Vector[(DxDataObject, DxObjectDescribe)]] = {
    val t0 = System.nanoTime()
    val dxAppletsInProject: Map[DxDataObject, DxObjectDescribe] =
      DxFindDataObjects(dxApi, None)
        .apply(Some(dxProject),
               None,
               recurse = true,
               Some("applet"),
               Vector(CHECKSUM_PROP),
               allExecutableNames.toVector,
               withInputOutputSpec = false,
               Vector.empty,
               Set.empty)
    val nrApplets = dxAppletsInProject.size
    val t1 = System.nanoTime()
    val diffMSec = (t1 - t0) / (1000 * 1000)
    dxApi.logger.trace(
        s"Found ${nrApplets} applets matching expected names in project ${dxProject.getId} (${diffMSec} millisec)"
    )

    dxAppletsInProject.foldLeft(Map.empty[String, Vector[(DxDataObject, DxObjectDescribe)]]) {
      case (accu, (dxObj, desc)) =>
        val chksum = desc.properties.flatMap { p =>
          p.get(CHECKSUM_PROP)
        }
        chksum match {
          case None                                  => accu
          case Some(digest) if accu.contains(digest) =>
            // Digest collision
            accu + (digest -> (accu(digest) :+ (dxObj, desc)))
          case Some(digest) =>
            accu + (digest -> Vector((dxObj, desc)))
        }
    }
  }

  def lookup(execName: String): Vector[DxObjectInfo] = {
    objDir.get(execName) match {
      case None    => Vector.empty
      case Some(v) => v
    }
  }

  // Search for an executable named [execName], with a specific
  // checksum anywhere in the project. This could save recompilation.
  //
  // Note: in case of checksum collision, there could be several hits.
  // Return only the one that starts with the name we are looking for.
  def lookupOtherVersions(execName: String,
                          digest: String): Option[(DxDataObject, DxObjectDescribe)] = {
    val checksumMatches = projectWideExecutableDir.get(digest) match {
      case None      => return None
      case Some(vec) => vec
    }
    val checksumAndNameMatches = checksumMatches.filter {
      case (_, dxDesc) =>
        dxDesc.name.startsWith(execName)
    }
    checksumAndNameMatches.headOption
  }

  def insert(name: String, dxObj: DxDataObject, digest: String): Unit = {
    val aInfo = DxObjectInfo(name, LocalDateTime.now, dxObj, Some(digest))
    objDir += (name -> Vector(aInfo))
  }

  // create a folder, if it does not already exist.
  private def newFolder(fullPath: String): Unit = {
    if (!(folders contains fullPath)) {
      dxProject.newFolder(fullPath, parents = true)
      folders += fullPath
    }
  }

  // Move an object into an archive directory. If the object
  // is an applet, for example /A/B/C/GLnexus, move it to
  //     /A/B/C/Applet_archive/GLnexus (Day Mon DD hh:mm:ss year)
  // If the object is a workflow, move it to
  //     /A/B/C/Workflow_archive/GLnexus (Day Mon DD hh:mm:ss year)
  //
  // Examples:
  //   GLnexus (Fri Aug 19 18:01:02 2016)
  //   GLnexus (Mon Mar  7 15:18:14 2016)
  //
  // Note: 'dx build' does not support workflow archiving at the moment.
  def archiveDxObject(objInfo: DxObjectInfo): Unit = {
    dxApi.logger.trace(s"Archiving ${objInfo.name} ${objInfo.dxObj.getId}")
    val dxClass: String = objInfo.dxClass
    val destFolder = folder ++ "/." ++ dxClass + "_archive"

    // move the object to the new location
    newFolder(destFolder)
    dxProject.moveObjects(Vector(objInfo.dxObj), destFolder)

    // add the date to the object name
    val formatter = DateTimeFormatter.ofPattern("EE MMM dd kk:mm:ss yyyy")
    val crDateStr = objInfo.crDate.format(formatter)
    val req = Map(
        "project" -> JsString(dxProject.getId),
        "name" -> JsString(s"${objInfo.name} ${crDateStr}")
    )

    dxClass match {
      case "Workflow" =>
        dxApi.workflowRename(objInfo.dxObj.getId, req)
      case "Applet" =>
        dxApi.appletRename(objInfo.dxObj.getId, req)
      case other => throw new Exception(s"Unkown class ${other}")
    }
  }
}
