package com.luxand.facerecognition;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import com.ipaulpro.afilechooser.utils.FileUtils;
import com.luxand.FSDK;
import com.luxand.FSDK.HTracker;

import java.text.MessageFormat;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MainActivity extends Activity implements OnClickListener {
    private static final int RESULT_LOAD_IMAGE = 1;
    private Preview mPreview;
    private ProcessImageAndDrawResults mDraw;
    private final String database = "Memory50.dat";
    private final String help_text = "Luxand Face Recognition\n\nJust tap any detected face and name it. The app will recognize this face further. For best results, hold the device at arm's length. You may slowly rotate the head for the app to memorize you at multiple views. The app can memorize several persons. If a face is not recognized, tap and name it again.\n\nThe SDK is available for mobile developers: www.luxand.com/facesdk";
    private String faceToMatch;

    public void showErrorAndClose(String error, int code) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(error + ": " + code)
                .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                })
                .show();
    }

    public void showMessage(String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message)
                .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                    }
                })
                .setCancelable(false) // cancel with button only
                .show();
    }


    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int res = FSDK.ActivateLibrary("TBfJd0P4Oxrpn3PmTdMBT/8b8XZx3AX4Ws/Y7Cjl0G46FdMAwLzPdlolMFpr72ydZ0mizsPODNsd8GBgWZ4ztv0Q8F8Cyh7TqdqlSSIYvMt72FAxCf0t2E/3mJ3WSflSCcsnCwgPIIo1nbisPreuwwHRAWkQVVYIHnMLZsYaQ0U=");
        if (res != FSDK.FSDKE_OK) {
            showErrorAndClose("FaceSDK activation failed", res);
        } else {
            FSDK.Initialize();

            // Hide the window title (it is done in manifest too)
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
            requestWindowFeature(Window.FEATURE_NO_TITLE);

            // Lock orientation
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

            // Camera layer and drawing layer
            mDraw = new ProcessImageAndDrawResults(this);
            mPreview = new Preview(this, mDraw);
            mDraw.mTracker = new HTracker();
            String templatePath = this.getApplicationInfo().dataDir + "/" + database;
            if (FSDK.FSDKE_OK != FSDK.LoadTrackerMemoryFromFile(mDraw.mTracker, templatePath)) {
                res = FSDK.CreateTracker(mDraw.mTracker);
                if (FSDK.FSDKE_OK != res) {
                    showErrorAndClose("Error creating tracker", res);
                }
            }
            int errpos[] = new int[1];
            FSDK.SetTrackerMultipleParameters(mDraw.mTracker, "DetectFacialFeatures=true;" +
                    "ContinuousVideoFeed=true;" +
                    "RecognitionPrecision=0;" +
                    "Threshold=0.997;" +
                    "Threshold2=0.9995;" +
                    "ThresholdFeed=0.97;" +
                    "MemoryLimit=1000;" +
                    "HandleArbitraryRotations=false;" +
                    "DetermineFaceRotationAngle=false;" +
                    "InternalResizeWidth=70;" +
                    "FaceDetectionThreshold=5;", errpos);
            if (errpos[0] != 0) {
                showErrorAndClose("Error setting tracker parameters, position", errpos[0]);
            }

            setContentView(mPreview); //creates MainActivity contents
            addContentView(mDraw, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

            // Menu
            LayoutInflater inflater = (LayoutInflater) this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View buttons = inflater.inflate(R.layout.bottom_menu, null);
            buttons.findViewById(R.id.choosePicture).setOnClickListener(this);
            addContentView(buttons, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    private static final int REQUEST_CHOOSER = 1234;

    @Override
    public void onClick(View view) {
        // Create the ACTION_GET_CONTENT Intent
        Intent getContentIntent = FileUtils.createGetContentIntent();

        Intent intent = Intent.createChooser(getContentIntent, "Select a file");
        startActivityForResult(intent, REQUEST_CHOOSER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CHOOSER:
                if (resultCode == RESULT_OK) {
                    final Uri uri = data.getData();
                    // Get the File path from the Uri
                    String path = FileUtils.getPath(this, uri);
                    this.setFaceToMatch(path);
                }
                break;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        pauseProcessingFrames();
        String templatePath = this.getApplicationInfo().dataDir + "/" + database;
        FSDK.SaveTrackerMemoryToFile(mDraw.mTracker, templatePath);
    }

    @Override
    public void onResume() {
        super.onResume();
        resumeProcessingFrames();
    }

    private void pauseProcessingFrames() {
        mDraw.mStopping = 1;

        // It is essential to limit wait time, because mStopped will not be set to 0, if no frames are feeded to mDraw
        for (int i = 0; i < 100; ++i) {
            if (mDraw.mStopped != 0) break;
            try {
                Thread.sleep(10);
            } catch (Exception ex) {
            }
        }
    }

    private void resumeProcessingFrames() {
        mDraw.mStopped = 0;
        mDraw.mStopping = 0;
    }

    public void setFaceToMatch(String picturePath) {
        if (picturePath == null) {
            Log.w("setFaceToMatch", "picturePath==null");
            return;
        }
        ImageView imageView = (ImageView) findViewById(R.id.faceToMatch);
        imageView.setImageURI(Uri.parse(picturePath));
        this.mDraw.setFaceToMatch(picturePath);
    }
}


class FaceRectangle {
    public int x1, y1, x2, y2;
}

// Draw graphics on top of the video
class ProcessImageAndDrawResults extends View {
    public HTracker mTracker;

    final int MAX_FACES = 5;
    final FaceRectangle[] mFacePositions = new FaceRectangle[MAX_FACES];
    final long[] mIDs = new long[MAX_FACES];
    final Lock faceLock = new ReentrantLock();
    int mTouchedIndex;
    long mTouchedID;
    int mStopping;
    int mStopped;

    Context mContext;
    Paint mPaintGreen, mPaintBlue, mPaintBlueTransparent;
    byte[] mYUVData;
    byte[] mRGBData;
    int mImageWidth, mImageHeight;
    boolean first_frame_saved;
    boolean rotated;
    private FSDK.FSDK_FaceTemplate faceToMatch;

    int GetFaceFrame(FSDK.FSDK_Features Features, FaceRectangle fr) {
        if (Features == null || fr == null)
            return FSDK.FSDKE_INVALID_ARGUMENT;

        float u1 = Features.features[0].x;
        float v1 = Features.features[0].y;
        float u2 = Features.features[1].x;
        float v2 = Features.features[1].y;
        float xc = (u1 + u2) / 2;
        float yc = (v1 + v2) / 2;
        int w = (int) Math.pow((u2 - u1) * (u2 - u1) + (v2 - v1) * (v2 - v1), 0.5);

        fr.x1 = (int) (xc - w * 1.6 * 0.9);
        fr.y1 = (int) (yc - w * 1.1 * 0.9);
        fr.x2 = (int) (xc + w * 1.6 * 0.9);
        fr.y2 = (int) (yc + w * 2.1 * 0.9);
        if (fr.x2 - fr.x1 > fr.y2 - fr.y1) {
            fr.x2 = fr.x1 + fr.y2 - fr.y1;
        } else {
            fr.y2 = fr.y1 + fr.x2 - fr.x1;
        }
        return 0;
    }


    public ProcessImageAndDrawResults(Context context) {
        super(context);

        mTouchedIndex = -1;

        mStopping = 0;
        mStopped = 0;
        rotated = false;
        mContext = context;
        mPaintGreen = new Paint();
        mPaintGreen.setStyle(Paint.Style.FILL);
        mPaintGreen.setColor(Color.GREEN);
        mPaintGreen.setTextSize(25);
        mPaintGreen.setTextAlign(Align.CENTER);
        mPaintBlue = new Paint();
        mPaintBlue.setStyle(Paint.Style.FILL);
        mPaintBlue.setColor(Color.BLUE);
        mPaintBlue.setTextSize(25);
        mPaintBlue.setTextAlign(Align.CENTER);

        mPaintBlueTransparent = new Paint();
        mPaintBlueTransparent.setStyle(Paint.Style.STROKE);
        mPaintBlueTransparent.setStrokeWidth(2);
        mPaintBlueTransparent.setColor(Color.BLUE);
        mPaintBlueTransparent.setTextSize(25);

        //mBitmap = null;
        mYUVData = null;
        mRGBData = null;

        first_frame_saved = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mStopping == 1) {
            mStopped = 1;
            super.onDraw(canvas);
            return;
        }

        if (mYUVData == null || mTouchedIndex != -1) {
            super.onDraw(canvas);
            return; //nothing to process or name is being entered now
        }

        int canvasWidth = canvas.getWidth();
        //int canvasHeight = canvas.getHeight();

        // Convert from YUV to RGB
        decodeYUV420SP(mRGBData, mYUVData, mImageWidth, mImageHeight);

        // Load image to FaceSDK
        FSDK.HImage Image = new FSDK.HImage();
        FSDK.FSDK_IMAGEMODE imagemode = new FSDK.FSDK_IMAGEMODE();
        imagemode.mode = FSDK.FSDK_IMAGEMODE.FSDK_IMAGE_COLOR_24BIT;
        FSDK.LoadImageFromBuffer(Image, mRGBData, mImageWidth, mImageHeight, mImageWidth * 3, imagemode);
        FSDK.MirrorImage(Image, false);
        FSDK.HImage RotatedImage = new FSDK.HImage();
        FSDK.CreateEmptyImage(RotatedImage);

        //it is necessary to work with local variables (onDraw called not the time when mImageWidth,... being reassigned, so swapping mImageWidth and mImageHeight may be not safe)
        int ImageWidth = mImageWidth;
        //int ImageHeight = mImageHeight;
        if (rotated) {
            ImageWidth = mImageHeight;
            //ImageHeight = mImageWidth;
            FSDK.RotateImage90(Image, -1, RotatedImage);
        } else {
            FSDK.CopyImage(Image, RotatedImage);
        }
        FSDK.FreeImage(Image);

        // Save first frame to gallery to debug (e.g. rotation angle)
        /*
		if (!first_frame_saved) {				
			first_frame_saved = true;
			String galleryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath();
			FSDK.SaveImageToFile(RotatedImage, galleryPath + "/first_frame.jpg"); //frame is rotated!
		}
		*/

        long IDs[] = new long[MAX_FACES];
        long face_count[] = new long[1];
        float similarities[] = new float[MAX_FACES];
        FSDK.FSDK_Features features[] = new FSDK.FSDK_Features[MAX_FACES];

        FSDK.FeedFrame(mTracker, 0, RotatedImage, face_count, IDs);


        faceLock.lock();

        for (int i = 0; i < MAX_FACES; ++i) {
            mFacePositions[i] = new FaceRectangle();
            mFacePositions[i].x1 = 0;
            mFacePositions[i].y1 = 0;
            mFacePositions[i].x2 = 0;
            mFacePositions[i].y2 = 0;
            mIDs[i] = IDs[i];
        }

        float ratio = (canvasWidth * 1.0f) / ImageWidth;
        for (int i = 0; i < (int) face_count[0]; ++i) {
            FSDK.FSDK_Features Eyes = new FSDK.FSDK_Features();
            FSDK.GetTrackerEyes(mTracker, 0, mIDs[i], Eyes);
            GetFaceFrame(Eyes, mFacePositions[i]);
            mFacePositions[i].x1 *= ratio;
            mFacePositions[i].y1 *= ratio;
            mFacePositions[i].x2 *= ratio;
            mFacePositions[i].y2 *= ratio;

            if (this.faceToMatch == null) {
                continue;
            }

            FSDK.FSDK_FaceTemplate capturedFaceTemplate = new FSDK.FSDK_FaceTemplate();
            int result = FSDK.GetFaceTemplateUsingEyes(RotatedImage, Eyes, capturedFaceTemplate);
            if (result != 0) {
                continue;
            }
            float[] similarity = new float[1];
            FSDK.MatchFaces(this.faceToMatch, capturedFaceTemplate, similarity);
            similarities[i] = similarity[0];

            features[i] = new FSDK.FSDK_Features();
            result = FSDK.GetTrackerFacialFeatures(mTracker, 0, mIDs[i], features[i]);
            if (result != 0) {
                continue;
            }
        }

        FSDK.FreeImage(RotatedImage);

        faceLock.unlock();

        // Mark and name faces
        for (int i = 0; i < face_count[0]; ++i) {
            canvas.drawRect(
                    mFacePositions[i].x1,
                    mFacePositions[i].y1,
                    mFacePositions[i].x2,
                    mFacePositions[i].y2,
                    mPaintBlueTransparent);

            canvas.drawText(MessageFormat.format("similarity: {0,number,#.##%}", similarities[i]),
                    (mFacePositions[i].x1 + mFacePositions[i].x2) / 2,
                    mFacePositions[i].y2 + 40,
                    mPaintGreen);

            if (features[i] == null) {
                continue;
            }
            for (FSDK.TPoint point : features[i].features) {
                int cx = (int) (point.x * ratio);
                int cy = (int) (point.y * ratio);
                canvas.drawCircle(cx, cy, 5, mPaintGreen);
            }
        }

        super.onDraw(canvas);
    } // end onDraw method

    static public void decodeYUV420SP(byte[] rgb, byte[] yuv420sp, int width, int height) {
        final int frameSize = width * height;
        int yp = 0;
        for (int j = 0; j < height; j++) {
            int uvp = frameSize + (j >> 1) * width, u = 0, v = 0;
            for (int i = 0; i < width; i++) {
                int y = (0xff & ((int) yuv420sp[yp])) - 16;
                if (y < 0) y = 0;
                if ((i & 1) == 0) {
                    v = (0xff & yuv420sp[uvp++]) - 128;
                    u = (0xff & yuv420sp[uvp++]) - 128;
                }
                int y1192 = 1192 * y;
                int r = (y1192 + 1634 * v);
                int g = (y1192 - 833 * v - 400 * u);
                int b = (y1192 + 2066 * u);
                if (r < 0) r = 0;
                else if (r > 262143) r = 262143;
                if (g < 0) g = 0;
                else if (g > 262143) g = 262143;
                if (b < 0) b = 0;
                else if (b > 262143) b = 262143;

                rgb[3 * yp] = (byte) ((r >> 10) & 0xff);
                rgb[3 * yp + 1] = (byte) ((g >> 10) & 0xff);
                rgb[3 * yp + 2] = (byte) ((b >> 10) & 0xff);
                ++yp;
            }
        }
    }

    public void setFaceToMatch(String picturePath) {
        Log.d("picturePath", picturePath);
        FSDK.HImage image = new FSDK.HImage();
        int result = FSDK.LoadImageFromFile(image, picturePath);
        Log.d("LoadImageFromFile", String.format("%d", result));
        FSDK.FSDK_FaceTemplate faceTemplate = new FSDK.FSDK_FaceTemplate();
        result = FSDK.GetFaceTemplate(image, faceTemplate);
        Log.d("GetFaceTemplate", String.format("%d", result));
        this.faceToMatch = (result == 0) ? faceTemplate : null;
        FSDK.FreeImage(image);
    }
} // end of ProcessImageAndDrawResults class


// Show video from camera and pass frames to ProcessImageAndDraw class
class Preview extends SurfaceView implements SurfaceHolder.Callback {
    Context mContext;
    SurfaceHolder mHolder;
    Camera mCamera;
    ProcessImageAndDrawResults mDraw;
    boolean mFinished;

    Preview(Context context, ProcessImageAndDrawResults draw) {
        super(context);
        mContext = context;
        mDraw = draw;

        //Install a SurfaceHolder.Callback so we get notified when the underlying surface is created and destroyed.
        mHolder = getHolder();
        mHolder.addCallback(this);
    }

    //SurfaceView callback
    public void surfaceCreated(SurfaceHolder holder) {
        mFinished = false;

        // Find the ID of the camera
        int cameraId = 0;
        boolean frontCameraFound = false;
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, cameraInfo);
            //if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK)
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                cameraId = i;
                frontCameraFound = true;
            }
        }

        if (frontCameraFound) {
            mCamera = Camera.open(cameraId);
        } else {
            mCamera = Camera.open();
        }

        try {
            mCamera.setPreviewDisplay(holder);

            // Preview callback used whenever new viewfinder frame is available
            mCamera.setPreviewCallback(new PreviewCallback() {
                public void onPreviewFrame(byte[] data, Camera camera) {
                    if ((mDraw == null) || mFinished)
                        return;

                    if (mDraw.mYUVData == null) {
                        // Initialize the draw-on-top companion
                        Camera.Parameters params = camera.getParameters();
                        mDraw.mImageWidth = params.getPreviewSize().width;
                        mDraw.mImageHeight = params.getPreviewSize().height;
                        mDraw.mRGBData = new byte[3 * mDraw.mImageWidth * mDraw.mImageHeight];
                        mDraw.mYUVData = new byte[data.length];
                    }

                    // Pass YUV data to draw-on-top companion
                    System.arraycopy(data, 0, mDraw.mYUVData, 0, data.length);
                    mDraw.invalidate();
                }
            });
        } catch (Exception exception) {
            AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
            builder.setMessage("Cannot open camera")
                    .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            android.os.Process.killProcess(android.os.Process.myPid());
                        }
                    })
                    .show();
            if (mCamera != null) {
                mCamera.release();
                mCamera = null;
            }
        }
    }

    //SurfaceView callback
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Surface will be destroyed when we return, so stop the preview.
        // Because the CameraDevice object is not a shared resource, it's very
        // important to release it when the activity is paused.
        mFinished = true;
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    //SurfaceView callback, configuring camera
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (mCamera == null) return;

        // Now that the size is known, set up the camera parameters and begin
        // the preview.
        Camera.Parameters parameters = mCamera.getParameters();

        //Keep uncommented to work correctly on phones:
        //This is an undocumented although widely known feature
		/**/
        if (this.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) {
            parameters.set("orientation", "portrait");
            mCamera.setDisplayOrientation(90); // For Android 2.2 and above
            mDraw.rotated = true;
        } else {
            parameters.set("orientation", "landscape");
            mCamera.setDisplayOrientation(0); // For Android 2.2 and above
        }
		/**/

        Camera.Size previewSize = parameters.getPreviewSize();
        makeResizeForCameraAspect(1.0f / ((1.0f * previewSize.width) / previewSize.height));
        //try to set preferred parameters
        try {
            parameters.setPreviewFrameRate(10);
            parameters.setSceneMode(Camera.Parameters.SCENE_MODE_PORTRAIT);
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            mCamera.setParameters(parameters);
        } catch (Exception ex) {
        }
        mCamera.startPreview();
    }

    private void makeResizeForCameraAspect(float cameraAspectRatio) {
        LayoutParams layoutParams = this.getLayoutParams();
        int matchParentWidth = this.getWidth();
        int newHeight = (int) (matchParentWidth / cameraAspectRatio);
        if (newHeight != layoutParams.height) {
            layoutParams.height = newHeight;
            layoutParams.width = matchParentWidth;
            this.setLayoutParams(layoutParams);
            this.invalidate();
        }
    }
} // end of Preview class
