package org.tensorflow.lite.examples.detection;

import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.extensions.HdrImageCaptureExtender;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * partly source: https://github.com/akhilbattula/android-camerax-java
 */

public abstract class CameraXActivity extends AppCompatActivity {

    private Executor executor = Executors.newSingleThreadExecutor();
    private int REQUEST_CODE_PERMISSIONS = 196;
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA", "android.permission.WRITE_EXTERNAL_STORAGE"};

    protected int previewWidth = 0;
    protected int previewHeight = 0;
    private int currentOrientation = 0;

    private Handler handler;
    private HandlerThread handlerThread;
    protected Bitmap rgbFrameBitmap;

    private PreviewView previewView;
    private Button captureImage;
    private TextView predView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        previewView = findViewById(R.id.previewView);
        captureImage = findViewById(R.id.imgCapture);
        predView = findViewById(R.id.predictionView);

        if (allPermissionsGranted()) {
            startOrientationListener();
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder().build();
        ImageCapture.Builder builder = new ImageCapture.Builder();

        //Vendor-Extensions (The CameraX extensions dependency in build.gradle)
        HdrImageCaptureExtender hdrImageCaptureExtender = HdrImageCaptureExtender.create(builder);

        // Query if extension is available (optional).
        if (hdrImageCaptureExtender.isExtensionAvailable(cameraSelector)) {
            // Enable the extension if available.
            hdrImageCaptureExtender.enableExtension(cameraSelector);
        }

        final ImageCapture imageCapture = builder
                .setTargetRotation(this.getWindowManager().getDefaultDisplay().getRotation())
                .build();

        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis, imageCapture);

        preview.setSurfaceProvider(previewView.createSurfaceProvider());

        captureImage.setOnClickListener(v -> imageCapture.takePicture(executor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            @SuppressLint("UnsafeExperimentalUsageError")
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                rgbFrameBitmap = getBitmap(image);
                image.close();

                // only init detector once
                if (previewWidth == 0) {
                    previewWidth = rgbFrameBitmap.getWidth();
                    previewHeight = rgbFrameBitmap.getHeight();
                    initDetector();
                }
                processImage();
            }

            @Override
            public void onError(@NonNull final ImageCaptureException exception) {
                exception.printStackTrace();
            }
        }));
    }

    // TODO no error check, test on different devices
    private Bitmap getBitmap(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        buffer.rewind();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        byte[] clonedBytes = bytes.clone();
        return BitmapFactory.decodeByteArray(clonedBytes, 0, clonedBytes.length);
    }

    private void startOrientationListener() {
        new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation >= 330 || orientation < 30)
                    currentOrientation = Surface.ROTATION_0;
                else if (orientation >= 60 && orientation < 120)
                    currentOrientation = Surface.ROTATION_90;
                else if (orientation >= 150 && orientation < 210)
                    currentOrientation = Surface.ROTATION_180;
                else if (orientation >= 240 && orientation < 300)
                    currentOrientation = Surface.ROTATION_270;
            }
        }.enable();
    }

    protected int getOrientationDegrees() {
        return currentOrientation * 90;
    }

    protected void setPredictionView(String prediction) {
        predView.setText(prediction);
    }

    protected abstract void processImage();

    protected abstract void initDetector();

    /**
     * -------------------------------------------------
     * -------------- Permission Handling --------------
     * -------------------------------------------------
     */

    private boolean allPermissionsGranted() {

        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                this.finish();
            }
        }
    }


    /**
     * -------------------------------------------------
     * ---------------- Thread Handling ----------------
     * -------------------------------------------------
     */

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

    @Override
    public synchronized void onStart() {
        super.onStart();
    }

    @Override
    public synchronized void onResume() {
        super.onResume();

        handlerThread = new HandlerThread("inference");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    @Override
    public synchronized void onPause() {
        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (final InterruptedException e) {
            e.printStackTrace();
        }

        super.onPause();
    }

    @Override
    public synchronized void onStop() {
        super.onStop();
    }

    @Override
    public synchronized void onDestroy() {
        super.onDestroy();
    }
}
