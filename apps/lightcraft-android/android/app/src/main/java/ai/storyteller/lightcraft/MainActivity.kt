package ai.storyteller.lightcraft

import ai.storyteller.lightcraft.ui.AppViewModel
import ai.storyteller.lightcraft.ui.LightcraftRoot
import ai.storyteller.lightcraft.ui.LightcraftTheme
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.importUris(it) }

    // raw files are often not in the photo picker: any document, the engine tells what it can read
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importUris(it) }

    // a photo taken with the phone's camera app: written to a file of ours, then moved into the library
    private var shot: File? = null
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = shot ?: return@registerForActivityResult
        shot = null
        if (taken && file.length() > 0) vm.importShot(file) else file.delete()
    }

    // The manifest declares CAMERA (for the pairing page's QR scanner), and an app that declares it must hold it
    // to start the camera app too, so the camera button asks first
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else vm.tell("Allow the camera to take a photo from LightCraft Cloud.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // always dark, whatever the system theme: light status icons on the dark bars
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // back from the camera app after the system closed LightCraft meanwhile
        shot = savedInstanceState?.getString(SHOT)?.let(::File)
        if (savedInstanceState == null) importShared(intent)
        setContent {
            LightcraftTheme {
                LightcraftRoot(
                    vm,
                    onPickPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPickFiles = { pickFiles.launch(arrayOf("image/*", "application/octet-stream")) },
                    onCamera = ::openCamera,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shot?.let { outState.putString(SHOT, it.path) }
    }

    /** The phone's camera app, for a photo that goes straight into the library. */
    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera() else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg")
        shot = file
        try {
            takePhoto.launch(FileProvider.getUriForFile(this, "$packageName.files", file))
        } catch (e: ActivityNotFoundException) {
            shot = null
            vm.tell("There's no camera app to take a photo with.")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importShared(intent)
    }

    /** Photos shared to LightCraft from another app are imported; one it opens or edits with LightCraft opens in the editor. */
    private fun importShared(intent: Intent?) {
        if (intent?.action == Intent.ACTION_EDIT || intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { vm.openSharedPhoto(it) }
            return
        }
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        vm.importUris(uris)
    }

    private companion object {
        const val SHOT = "camera-shot"
    }
}
