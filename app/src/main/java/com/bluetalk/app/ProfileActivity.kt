package com.bluetalk.app

import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bluetalk.app.auth.AuthStore
import com.bluetalk.app.databinding.ActivityProfileBinding
import kotlin.math.max
import kotlin.math.min

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private var imageUri: Uri? = null

    // maximize toggle state
    private var isMaximized = false

    // drag state
    private var downX = 0f
    private var downY = 0f
    private var startTx = 0f
    private var startTy = 0f

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                imageUri = it
                binding.profileImage.setImageURI(it)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- Load current profile
        val store = AuthStore.get(this)
        val profile = store.currentProfile()

        binding.inputName.setText(profile?.name)
        binding.inputNick.setText(profile?.nick)
        binding.inputBio.setText(profile?.bio)
        profile?.photoUri?.let { binding.profileImage.setImageURI(Uri.parse(it)) }

        // --- Pick image
        binding.btnPickImage.setOnClickListener { pickImage.launch("image/*") }

        // --- Save changes
        binding.btnSave.setOnClickListener {
            val updated = profile?.copy(
                name = binding.inputName.text.toString().trim(),
                nick = binding.inputNick.text.toString().trim(),
                bio = binding.inputBio.text.toString().trim(),
                photoUri = imageUri?.toString() ?: profile.photoUri
            )
            if (updated != null) {
                store.saveProfile(updated)
                Toast.makeText(this, "Profile updated!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // --- Mac-style controls (optional views; no crash if missing)
        val btnClose: View? = safeFind("btnClose")
        val btnDrag: View? = safeFind("btnDrag")
        val btnMaximize: View? = safeFind("btnMaximize")
        val titleBar: View? = safeFind("titleBar")

        // the view we will move around; prefer a dedicated container if you added one
        val panel: View = (safeFind("panelRoot") ?: binding.root)

        btnClose?.setOnClickListener { finish() }

        // make draggable via yellow button AND the whole title bar
        val dragListener = View.OnTouchListener { _, ev ->
            handleDrag(ev, panel)
        }
        btnDrag?.setOnTouchListener(dragListener)
        titleBar?.setOnTouchListener(dragListener)

        btnMaximize?.setOnClickListener {
            toggleMaximize(panel)
        }
    }

    // ---- Helpers -------------------------------------------------------------

    /** Safely find a view by id name from the layout; returns null if not present. */
    private fun safeFind(idName: String): View? {
        val id = resources.getIdentifier(idName, "id", packageName)
        return if (id != 0) findViewById(id) else null
    }

    /** Toggle between fullscreen and floating sizes. */
    private fun toggleMaximize(panel: View) {
        isMaximized = !isMaximized
        if (isMaximized) {
            // Fill screen
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            // reset any translations so it centers
            panel.translationX = 0f
            panel.translationY = 0f
        } else {
            // Let content wrap; if your Activity uses a dialog theme, this will look like a floating panel
            window.setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /** Handle dragging the panel within the window by applying translation. */
    private fun handleDrag(ev: MotionEvent, panel: View): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                startTx = panel.translationX
                startTy = panel.translationY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                val targetTx = startTx + dx
                val targetTy = startTy + dy

                // optional: keep within window bounds (best-effort)
                val parent = panel.rootView
                val bounds = intArrayOf(0, 0)
                parent.getLocationOnScreen(bounds)
                val screenW = parent.width.toFloat()
                val screenH = parent.height.toFloat()

                val clampedTx = clamp(targetTx, -screenW / 2f, screenW / 2f)
                val clampedTy = clamp(targetTy, -screenH / 2f, screenH / 2f)

                panel.translationX = clampedTx
                panel.translationY = clampedTy
                return true
            }
        }
        return false
    }

    private fun clamp(v: Float, minV: Float, maxV: Float): Float = max(minV, min(v, maxV))
}
