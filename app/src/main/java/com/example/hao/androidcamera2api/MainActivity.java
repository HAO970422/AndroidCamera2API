package com.example.hao.androidcamera2api;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.util.SparseArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

public class MainActivity extends AppCompatActivity {
    private String TAG = "MainActivity";

    private Button btnCapture;
    private TextureView textureView;

    // Check state orientation of output image
    @SuppressLint("UseSparseArrays")
    private static final SparseArray<Integer> ORIENTATIONS = new SparseArray<>();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size previewSize;
    private Size captureSize;

    // Save to File
    private File file;
    private ImageReader imageReader;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    // CameraDevice:具体的摄像头设备，有一系列参数（预览尺寸、拍照尺寸等），主要作用是创建CameraCaptureSession和CaptureRequest
    // CameraCaptureSession:相机捕获会话，用于处理拍照和预览的工作
    // CaptureRequest:捕获请求，定义输出缓冲区及显示界面（TextureView或SurfaceView等）
    CameraDevice.StateCallback stateCallBack = new CameraDevice.StateCallback() {
        @Override
        // 相机打开后会回调onOpened方法，在这个方法里开启预览
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera; // 打开成功，获取CameraDevice对象
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        assert textureView != null; // from Java1.4, boolean表达式为true则继续执行，否则抛出AssertError程序终止
        // Android普通窗口的绘图机制只是一层一层的，任何一个元素的局部刷新都会导致整个视图的重绘
        // SurfaceView的工作方式是创建一个置于应用窗口之后的新窗口，刷新的时候不需要重绘应用的窗口
        // 但SurfaceView的内容不在应用窗口上，不能用变换，也难以放在ListView或ScrollView中，故引入TextureView
        textureView.setSurfaceTextureListener(textureListener); // 实现SurfaceTextureListener接口，获取用于渲染内容的SurfaceTexture

        btnCapture = findViewById(R.id.btnCapture);
        btnCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePicture();  // 拍照动作
            }
        });

    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    private void openCamera() {
        // CameraManager ——> 参数 ——> 检查相机权限 ——> CameraManager.openCamera
        CameraManager cameraManager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = cameraManager.getCameraIdList()[CameraCharacteristics.LENS_FACING_FRONT]; // 后置camera
            // 返回的CameraCharacteristics对象封装了相机设备固有的的所有功能属性。可以通过该对象获取和设置相机的参数，如对焦方式、闪光灯设置等
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            // StreamConfigurationMap是管理camera支持的所有输出格式和尺寸
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            previewSize = map.getOutputSizes(SurfaceTexture.class)[0]; // 获取预览画面输出的尺寸
            captureSize = new Size(640, 480); // Capture image with custom size
            Size[] imgFormatSizes = map.getOutputSizes(ImageFormat.JPEG); // 获取图片输出的尺寸
            // 若jpegSize通过map.getOutputSizes已被赋值，则captureSize按照赋值结果，否则按照自定义
            if (imgFormatSizes != null && imgFormatSizes.length > 0) {
                captureSize = imgFormatSizes[0];
            }
            setupImageReader(); // ImageReader用于拍照所需

            // Check real time permission if run higher API 23，打开相机之前先判断是否有相机的权限
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_CAMERA_PERMISSION);
                return;
            }
            // 打开相机，获取CameraDevice对象（即stateCallBack）
            // param1是之前获取的cameraId；param2是当CameraDevice打开时的回调StateCallBack；param3决定了回调函数触发的线程，null为当前线程
            cameraManager.openCamera(cameraId, stateCallBack, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setupImageReader() {
        // Create a new reader for images of the desired size and format.
        // maxImages：should be as small as possible to limit memory use, 代表ImageReader中最多可以获取两帧图像流
        // JPG格式渲染数据量过大，预览帧会卡顿，故改用YV格式
        imageReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);
        // 监听ImageReader的事件，当imageReader中有内容时回调OnImageAvailableListener方法，参数就是预览帧数据
        ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = reader.acquireLatestImage();
                String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                file = new File(Environment.getExternalStorageDirectory() + "/" + timeStamp + ".jpg");
                try {
                    // 将帧数据转成字节数组，类似于Camera1的PreviewCallback回调的预览数据
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.capacity()];
                    buffer.get(bytes);
                    // Todo:拍照回调：新开一个线程来保存image数据(字节数组格式)
                    backgroundHandler.post(new ImageSaver(bytes));

                    // Todo:预览回调

                } finally {
                    if (image != null)
                        image.close();  // Note：一定要调用reader.acquireLatestImage和image.close()，否则画面会卡住
                }
            }
        };
        imageReader.setOnImageAvailableListener(readerListener, backgroundHandler);
    }

    private void createCameraPreview() {
        // 用TextureView显示相机预览数据，Camera2的预览和拍照数据都是使用CameraCaptureSession会话来请求的
        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
        assert surfaceTexture != null;
        // 设置TextureView的缓冲区大小
        surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        // 获取Surface显示预览数据
        Surface previewSurface = new Surface(surfaceTexture);

        try {
            // 创建CaptureRequestBuilder，TEMPLATE_PREVIEW表示预览请求
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface); // 设置Surface为预览数据的显示界面
            // Todo:当实现拍照时注释下行，否则预览数据会一直输出到imageReader，调用其OnImageAvailableListener方法
            //captureRequestBuilder.addTarget(imageReader.getSurface()); // CaptureRequest添加到imageReader的surface

            // 创建相机捕获会话，param1是捕获数据的输出Surface列表，
            // param2是状态回调接口，当它创建好后会回调onConfigured方法
            // param3用来确定Callback在哪个线程执行，为null的话就在当前线程
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        cameraCaptureSession = session;
                        // 设置反复捕获数据的请求，这样预览界面就会一直有数据显示
                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(MainActivity.this, "changed", Toast.LENGTH_SHORT).show();
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    public void takePicture() {
        try {
            // 创建请求拍照的CaptureRequest
            final CaptureRequest.Builder mCaptureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            mCaptureBuilder.addTarget(imageReader.getSurface()); // 设置CaptureRequest输出到imageReader
            int rotation = getWindowManager().getDefaultDisplay().getRotation(); // 获取屏幕方向
            mCaptureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

            // 此回调接口用于拍照结束时重启预览，因为拍照会导致预览停止
            CameraCaptureSession.CaptureCallback CaptureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    try {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                        // setRepeatingRequest重启预览，第一个参数captureRequestBuilder是之前开启预览的请求
                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
            };
            cameraCaptureSession.stopRepeating();
            cameraCaptureSession.capture(mCaptureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if(grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "You can't use camera without permission", Toast.LENGTH_SHORT).show();
                finish();
            }

        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if(textureView.isAvailable())
            openCamera();
        else
            textureView.setSurfaceTextureListener(textureListener);
    }

    @Override
    protected void onPause() {
        stopBackgroundThread();
        super.onPause();
    }

    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("Camera Background");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    public class ImageSaver implements Runnable {

        private byte[] bytes;

        public ImageSaver(byte[] b) {
            bytes = b;
        }

        @Override
        public void run() {
            try {
                OutputStream outputStream = new FileOutputStream(file);
                outputStream.write(bytes);
                Toast.makeText(MainActivity.this, "Saved "+file, Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}