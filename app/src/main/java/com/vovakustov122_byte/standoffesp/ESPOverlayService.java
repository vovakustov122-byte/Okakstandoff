package com.vovakustov122_byte.standoffesp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.core.app.NotificationCompat;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ESPOverlayService extends Service {

    private static final String CHANNEL_ID = "ESPChannel";
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private WindowManager windowManager;
    private FrameLayout overlayLayout;
    private ESPView espView;
    private HandlerThread frameHandlerThread;
    private Handler frameHandler;
    private int screenWidth, screenHeight;
    private int resultCode;
    private Intent data;

    @Override
    public void onCreate() {
        super.onCreate();
        OpenCVLoader.initDebug();
        createNotificationChannel();
        startForeground(1, createNotification());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        screenWidth = windowManager.getDefaultDisplay().getWidth();
        screenHeight = windowManager.getDefaultDisplay().getHeight();
        frameHandlerThread = new HandlerThread("FrameProcessor");
        frameHandlerThread.start();
        frameHandler = new Handler(frameHandlerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            resultCode = intent.getIntExtra("resultCode", 0);
            data = intent.getParcelableExtra("data");
            createOverlay();
            startScreenCapture();
            startFrameProcessing();
        }
        return START_STICKY;
    }

    private void createOverlay() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );

        overlayLayout = new FrameLayout(this);
        espView = new ESPView(this);
        overlayLayout.addView(espView);

        Button closeBtn = new Button(this);
        closeBtn.setText("X");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setBackgroundColor(Color.RED);
        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(100, 100);
        btnParams.gravity = Gravity.TOP | Gravity.END;
        closeBtn.setLayoutParams(btnParams);
        closeBtn.setOnClickListener(v -> stopESP());
        overlayLayout.addView(closeBtn);

        windowManager.addView(overlayLayout, params);
    }

    private void startScreenCapture() {
        MediaProjectionManager pm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = pm.getMediaProjection(resultCode, data);
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight,
                windowManager.getDefaultDisplay().getRefreshRate(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null
        );
    }

    private void startFrameProcessing() {
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            frameHandler.post(() -> processImage(image));
        }, frameHandler);
    }

    private void processImage(Image image) {
        Bitmap bitmap = imageToBitmap(image);
        Mat frame = new Mat();
        Utils.bitmapToMat(bitmap, frame);
        Mat hsv = new Mat();
        Imgproc.cvtColor(frame, hsv, Imgproc.COLOR_RGB2HSV);

        Scalar lowerRed1 = new Scalar(0, 100, 100);
        Scalar upperRed1 = new Scalar(10, 255, 255);
        Mat mask1 = new Mat();
        Core.inRange(hsv, lowerRed1, upperRed1, mask1);

        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(mask1, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        List<Rect> enemies = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            if (rect.width > 30 && rect.height > 60) {
                enemies.add(rect);
            }
        }

        espView.updateEnemies(enemies);

        frame.release();
        hsv.release();
        mask1.release();
        image.close();
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        Bitmap bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        return bitmap;
    }

    private class ESPView extends View {
        private List<Rect> enemies = new ArrayList<>();
        private Paint paint = new Paint();

        public ESPView(Context context) {
            super(context);
            paint.setColor(Color.RED);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5);
        }

        public void updateEnemies(List<Rect> newEnemies) {
            this.enemies = newEnemies;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (Rect rect : enemies) {
                canvas.drawRect(rect, paint);
                canvas.drawText("ENEMY", rect.left, rect.top - 10, paint);
            }
        }
    }

    private void stopESP() {
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (overlayLayout != null) windowManager.removeView(overlayLayout);
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "ESP Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Standoff ESP")
                .setContentText("ESP активен")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopESP();
        super.onDestroy();
    }
}
