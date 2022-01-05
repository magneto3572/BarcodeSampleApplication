package com.sample.barcode

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.util.Linkify
import android.util.DisplayMetrics
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.sample.barcode.databinding.ActivityMainBinding
import com.sample.barcode.databinding.BottomSheetBarcodeBinding
import com.sample.barcode.utils.*
import timber.log.Timber

@SuppressLint("UnsafeExperimentalUsageError", "UnsafeOptInUsageError")
class MainActivity : AppCompatActivity() {

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }
    private val viewModel by viewModels<MainViewModel>()
    private lateinit var camera : Camera
    private val cameraSelector by lazy {
        CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
    }

    private val screenAspectRatio by lazy {
        val metrics = DisplayMetrics().also { binding.previewView.display.getRealMetrics(it) }
        metrics.getAspectRatio()
    }

    private val executor by lazy { ContextCompat.getMainExecutor(this) }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) {
                setupCameraProvider()
            } else {
                showToast("Camera permission is needed to scan barcodes")
                finishAffinity()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this
        checkPermission()
        setupClickListeners()
    }

    init {
        lifecycleScope.launchWhenResumed {
            viewModel.cameraProvider.observe(this@MainActivity) {
                it?.let {
                    bindUseCase()
                }
            }
        }
    }

    private fun openBottomSheet(result: String) {
        val barcodeBottomSheetBinding = BottomSheetBarcodeBinding.inflate(layoutInflater)
        val barcodeBottomSheetDialog = BottomSheetDialog(this).apply {
            setContentView(barcodeBottomSheetBinding.root)
            setOnShowListener {
                (barcodeBottomSheetBinding.root.parent as ViewGroup).background = ColorDrawable(
                    Color.TRANSPARENT
                )
            }
            setOnDismissListener {
                bindUseCase()
            }
            show()
        }
        barcodeBottomSheetBinding.apply {
            textResult.text = result
            Linkify.addLinks(textResult,Linkify.ALL)
            btnGotIt.setOnClickListener {
                barcodeBottomSheetDialog.dismiss()
            }
        }
    }

    private fun setupClickListeners() {
        binding.imgFlash.setOnClickListener {
            when (camera.cameraInfo.torchState.value) {
                TorchState.ON -> camera.cameraControl.enableTorch(false)
                TorchState.OFF -> camera.cameraControl.enableTorch(true)
            }
        }
    }

    private fun bindUseCase() {
        val barcodeScanner = BarcodeScanning.getClient()

        val previewUseCase = Preview.Builder()
            .setTargetRotation(binding.previewView.display.rotation)
            .setTargetAspectRatio(screenAspectRatio)
            .build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

        val analysisUseCase = ImageAnalysis.Builder()
            .setTargetRotation(binding.previewView.display.rotation)
            .setTargetAspectRatio(screenAspectRatio)
            .build().also {
                it.setAnalyzer(
                    executor,
                    { imageProxy ->
                        processImageProxy(barcodeScanner, imageProxy)
                    }
                )
            }

        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(previewUseCase)
            .addUseCase(analysisUseCase)
            .build()

        try {
            viewModel.cameraProvider.value?.bindToLifecycle(
                this,
                cameraSelector,
                useCaseGroup
            )?.let {
                camera = it
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun processImageProxy(barcodeScanner: BarcodeScanner, imageProxy: ImageProxy) {
        imageProxy.image?.let { image ->
            val inputImage = InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodeList ->
                    if (!barcodeList.isNullOrEmpty()) {
                        Timber.i(barcodeList[0].rawValue)
                        viewModel.cameraProvider.value?.unbindAll()
                        openBottomSheet(barcodeList[0].rawValue!!) // Change this as required
                    }
                }.addOnFailureListener {
                    Timber.e(it)
                }.addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }


    private fun setupCameraProvider() {
        val cameraProvideFuture = ProcessCameraProvider.getInstance(this)
        cameraProvideFuture.addListener(
            { viewModel.setCameraProvider(cameraProvideFuture.get()) },
            executor
        )
    }

    private fun checkPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> setupCameraProvider()
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.CAMERA
            ) -> openPermissionRationaleDialog()
            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openPermissionRationaleDialog() {
        Snackbar.make(
            binding.root,
            "Camera permission is needed to scan barcodes",
            Snackbar.LENGTH_LONG
        ).setAction("Allow") {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }.show()
    }
}
