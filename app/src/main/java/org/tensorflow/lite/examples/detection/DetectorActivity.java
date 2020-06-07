/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.detection;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Size;
import android.util.TypedValue;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.tensorflow.lite.examples.detection.env.BorderedText;
import org.tensorflow.lite.examples.detection.env.ImageUtils;
import org.tensorflow.lite.examples.detection.env.Logger;
import org.tensorflow.lite.examples.detection.tflite.Classifier;
import org.tensorflow.lite.examples.detection.tflite.TFLiteObjectDetectionAPIModel;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraXActivity {
    private static final Logger LOGGER = new Logger();

    // Configuration values for the prepackaged SSD model.
    //private static final int TF_OD_API_INPUT_SIZE = 750;
    //TODO
    private static final int w = 360;
    private static final int h = 640;
    private static final int w2 = 600;
    private static final int h2 = 600;
    private static final boolean TF_OD_API_IS_QUANTIZED = false;
    private static final String TF_OD_API_MODEL_FILE = "box.tflite";
    private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/labels_box.txt";
    private static final String TF_OD_API_MODEL_FILE2 = "numbers.tflite";
    private static final String TF_OD_API_LABELS_FILE2 = "file:///android_asset/labels.txt";
    // Minimum detection confidence to track a detection.
    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.4f;
    private static final boolean MAINTAIN_ASPECT = false;
    private static final Size DESIRED_PREVIEW_SIZE = new Size(1080, 1920);
    private static final float TEXT_SIZE_DIP = 10;
    //OverlayView trackingOverlay;
    private Integer sensorOrientation;
    private int cnt = 0;

    private Classifier detector;
    private Classifier detector2;

    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap croppedBitmap2 = null;
    private Bitmap cropCopyBitmap = null;

    private boolean computingDetection = false;

    private long timestamp = 0;

    private Matrix frameToCropTransform;
    private Matrix frameToCropTransform2;
    private Matrix cropToFrameTransform;
    private Matrix cropToFrameTransform2;

    //private MultiBoxTracker tracker;

    private BorderedText borderedText;

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        final float textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        //tracker = new MultiBoxTracker(this);

        //int cropSize = TF_OD_API_INPUT_SIZE;

        try {
            detector = TFLiteObjectDetectionAPIModel.create(
                    getAssets(),
                    TF_OD_API_MODEL_FILE,
                    TF_OD_API_LABELS_FILE2,
                    //TF_OD_API_INPUT_SIZE,
                    w,
                    h,
                    TF_OD_API_IS_QUANTIZED);
            //cropSize = TF_OD_API_INPUT_SIZE;
            detector2 = TFLiteObjectDetectionAPIModel.create(
                    getAssets(),
                    TF_OD_API_MODEL_FILE2,
                    TF_OD_API_LABELS_FILE2,
                    //TF_OD_API_INPUT_SIZE,
                    w2,
                    h2,
                    TF_OD_API_IS_QUANTIZED);
        } catch (final IOException e) {
            e.printStackTrace();
            LOGGER.e(e, "Exception initializing classifier!");
            Toast toast = Toast.makeText(
                    getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        sensorOrientation = rotation - getOrientationMode();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        //croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(w, h, Config.ARGB_8888);

        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        //cropSize, cropSize,
                        w, h,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        //TODO my stuff, small bitmap
        croppedBitmap2 = Bitmap.createBitmap(w2, h2, Config.ARGB_8888);

        /*trackingOverlay = findViewById(R.id.tracking_overlay);
        trackingOverlay.addCallback(canvas -> {
            tracker.draw(canvas);
            if (isDebug()) {
                tracker.drawDebug(canvas);
            }
        });

        tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);

         */
    }

    @Override
    protected void processImage() {


        ++timestamp;
        final long currTimestamp = timestamp;
        //trackingOverlay.postInvalidate();

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage();
            return;
        }
        computingDetection = true;
        LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

        // TODO
        //rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);
        rgbFrameBitmap = camerainput;

        // uncommented for live processing
        //readyForNextImage();


        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);

        runInBackground(() -> {
            LOGGER.i("Running detection on image " + currTimestamp);

            //runOnUiThread(() -> setPredictionView("Performing recognition..."));

            final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);

            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
            final Canvas canvas1 = new Canvas(cropCopyBitmap);
            final Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setStyle(Style.STROKE);
            paint.setStrokeWidth(2.0f);

            final List<Classifier.Recognition> mappedRecognitions =
                    new LinkedList<>();


            for (final Classifier.Recognition result : results) {
                final RectF location = result.getLocation();
                if (location != null && result.getConfidence() >= MINIMUM_CONFIDENCE_TF_OD_API) {
                    canvas1.drawRect(location, paint);

                    cropToFrameTransform.mapRect(location);

                    result.setLocation(location);
                    mappedRecognitions.add(result);
                }
            }

            //TODO no recognition, skip
            // image is in horizontal mode
            // crop part of the bitmap
            final List<Classifier.Recognition> results2;
            Bitmap tagBitmap = null;
            if (!results.isEmpty() && results.get(0).getConfidence() >= MINIMUM_CONFIDENCE_TF_OD_API) {
                final Classifier.Recognition r = results.get(0);

                /** Testing square crop **/
                Rect rect = new Rect((int) r.getLocation().left, (int) r.getLocation().top, (int) r.getLocation().right, (int) r.getLocation().bottom);

                int crop_width = rect.right - rect.left;
                int crop_height = rect.bottom - rect.top;

                // TODO
                assert (crop_width > 0 && crop_height > 0);

                int crop_delta = crop_height - crop_width;
                // height smaller than width
                if (crop_delta < 0) {
                    crop_delta = -crop_delta;
                    if (rect.bottom + crop_delta < rgbFrameBitmap.getHeight())
                        rect.bottom += crop_delta;
                    else {
                        crop_delta -= rgbFrameBitmap.getHeight() - rect.bottom;
                        rect.bottom = rgbFrameBitmap.getHeight();
                        if (rect.top - crop_delta > 0)
                            rect.top -= crop_delta;
                        else
                            rect.top = 0;
                    }
                } else {
                    if (rect.right + crop_delta < rgbFrameBitmap.getWidth())
                        rect.right += crop_delta;
                    else {
                        crop_delta -= rgbFrameBitmap.getWidth() - rect.right;
                        rect.right = rgbFrameBitmap.getWidth();
                        if (rect.left - crop_delta > 0)
                            rect.left -= crop_delta;
                        else
                            rect.left = 0;
                    }
                }


                assert (rect.left < rect.right && rect.top < rect.bottom);

                //  Create our resulting image (150--50),(75--25) = 200x100px
                tagBitmap = Bitmap.createBitmap(rect.right - rect.left, rect.bottom - rect.top, Bitmap.Config.ARGB_8888);
                //  draw source bitmap into resulting image at given position:

                new Canvas(tagBitmap).drawBitmap(rgbFrameBitmap, -rect.left, -rect.top, null);
                //Bitmap tagBitmap = Bitmap.createBitmap(rgbFrameBitmap, (int) r.getLocation().left, (int) r.getLocation().top, (int) r.getLocation().width(), (int) r.getLocation().height());


                // store cropped image
                /*
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    tagBitmap.compress(Bitmap.CompressFormat.JPEG, 40, bytes);


                    //you can create a new file name "test.jpg" in sdcard folder.
                    File f = new File(Environment.getExternalStorageDirectory() + "/test2_" + cnt + ".jpg");
                    cnt++;

                    f.createNewFile();
                    //write the bytes in file
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(bytes.toByteArray());

                    // remember close the FileOutput
                    fo.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }*/
            }


            // TODO hacky
            if (tagBitmap == null)
                results2 = results;
            else {
                frameToCropTransform2 =
                        ImageUtils.getTransformationMatrix(
                                tagBitmap.getWidth(), tagBitmap.getHeight(),
                                //cropSize, cropSize,
                                w2, h2,
                                // TODO rotation + 90
                                getOrientationDegrees()+90, MAINTAIN_ASPECT);

                cropToFrameTransform2 = new Matrix();
                frameToCropTransform2.invert(cropToFrameTransform2);

                final Canvas canvas2 = new Canvas(croppedBitmap2);
                canvas2.drawBitmap(tagBitmap, frameToCropTransform2, null);


                results2 = detector2.recognizeImage(croppedBitmap2);
            }

            //final Canvas canvas2 = new Canvas(tagBitmap);
            //canvas2.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
/*
              if(!results.isEmpty() && results.get(0).getConfidence() >= MINIMUM_CONFIDENCE_TF_OD_API){
                final Classifier.Recognition r = results.get(0);
                Bitmap tagBitmap = Bitmap.createBitmap(rgbFrameBitmap, (int)r.getLocation().left, (int)r.getLocation().top, (int)r.getLocation().width(),
                          (int)r.getLocation().height());
                final Canvas canvas2 = new Canvas(tagBitmap);
                canvas2.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);

                //r.setLocation(location);
                mappedRecognitions.add(r);
              }*/

            // display bounding boxes
            //tracker.trackResults(mappedRecognitions, currTimestamp);
            //trackingOverlay.postInvalidate();

            computingDetection = false;

            // printing prediction on view


            /*******************************/

            List<Classifier.Recognition> res = new ArrayList<>();
            for (final Classifier.Recognition result : results2) {
                if (result.getConfidence() >= MINIMUM_CONFIDENCE_TF_OD_API) {
                    res.add(result);
                }
            }

            // sort recognized items after vertical position, add 2*numberheight to detect the small numbers first
            Collections.sort(res, (r1, r2) -> Float.compare(r1.getLocation().centerY() + r1.getLocation().height() * 2, r2.getLocation().centerY() + r2.getLocation().height() * 2));

            // if less than 3 numbers detected
            if (res.size() < 3) {
                runOnUiThread(() -> setPredictionView("Recognized: Nothing"));
                //switchBackToCallerActivity(new ArrayList<>());
                return;
            }

            // TODO remove boxes with same location
            // TODO make pretty, hacky style atm, not working for two hidden small numbers
            float num_height = res.get(0).getLocation().height();
            float pos1 = res.get(0).getLocation().centerY() + res.get(0).getLocation().centerX();
            float pos2 = res.get(1).getLocation().centerY() + res.get(1).getLocation().centerX();
            float pos3 = res.get(2).getLocation().centerY() + res.get(2).getLocation().centerX();


            // sort the two smallest y positions after x position
            ArrayList<Classifier.Recognition> sorted_nr = new ArrayList<>();

            // one small one big number
            if (Math.abs(pos1 - pos2) > num_height) {
                sorted_nr.add(res.remove(0));
                sorted_nr.add(null);    // add null for the unrecognized number
            }
            // two small numbers
            else if (Math.abs(pos1 - pos3) > num_height) {
                sorted_nr.add(res.remove(0));
                sorted_nr.add(res.remove(0));
                Collections.sort(sorted_nr, (r1, r2) -> Float.compare(r1.getLocation().centerX(), r2.getLocation().centerX()));
            }
            // else no small number, add two null elements for the unrecognized number
            else {
                sorted_nr.add(null);
                sorted_nr.add(null);
            }

            ArrayList<String> return_res = new ArrayList<>();

            // adding first two small numbers
            for (Classifier.Recognition r : sorted_nr) {
                if (r != null) return_res.add(r.getTitle());
                else return_res.add("-");
            }

            // sort the remaining positions after x position
            Collections.sort(res, (r1, r2) -> Float.compare(r1.getLocation().centerX(), r2.getLocation().centerX()));
            float comp_height = -1;

            for (int i = 0; i < 7; i++) {
                if (res.isEmpty()) {
                    return_res.add("-");
                    continue;
                }

                Classifier.Recognition result = res.get(0);

                // if number height smaller than 3/4 of the previous number, it is a small number
                if (result.getLocation().height() < comp_height * 0.75) {
                    if (return_res.size() < 6) {
                        return_res.add("-");
                        continue;
                    }
                } // initialize the height to compare
                else if (comp_height == -1)
                    comp_height = result.getLocation().height();
                return_res.add(res.remove(0).getTitle());
            }

                /*
                for (final Classifier.Recognition result : res) {
                    return_res.add(result.getTitle());
                }*/


            // printing prediction on view
            runOnUiThread(() -> setPredictionView("Recognized: " + return_res.toString()));
            //switchBackToCallerActivity(return_res);
        });





/*
            runOnUiThread(() -> {
                ArrayList<String> det_cards = new ArrayList<>();
                for (final Classifier.Recognition res : results2) {
                    if (res.getConfidence() >= MINIMUM_CONFIDENCE_TF_OD_API)
                        det_cards.add(res.getTitle());
                }
                setPredictionView("Recognized: " + det_cards.toString());
            });
        });*/
    }

    /*
    @Override
    protected int getLayoutId() {
        return R.layout.camera_connection_fragment_tracking;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }*/
}
