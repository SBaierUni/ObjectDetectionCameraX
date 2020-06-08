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
import android.util.Pair;
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
    // Configuration values for the prepackaged SSD model.
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

    private Classifier detector;
    private Classifier detector2;

    private Bitmap croppedBitmap = null;
    private Bitmap croppedBitmap2 = null;

    private boolean computingDetection = false;

    private Matrix frameToCropTransform;
    private Matrix frameToCropTransform2;
    private Matrix cropToFrameTransform;
    private Matrix cropToFrameTransform2;

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        try {
            detector = TFLiteObjectDetectionAPIModel.create(
                    getAssets(),
                    TF_OD_API_MODEL_FILE,
                    TF_OD_API_LABELS_FILE,
                    w,
                    h,
                    TF_OD_API_IS_QUANTIZED);
            detector2 = TFLiteObjectDetectionAPIModel.create(
                    getAssets(),
                    TF_OD_API_MODEL_FILE2,
                    TF_OD_API_LABELS_FILE2,
                    w2,
                    h2,
                    TF_OD_API_IS_QUANTIZED);
        } catch (final IOException e) {
            e.printStackTrace();
            Toast toast = Toast.makeText(
                    getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        croppedBitmap = Bitmap.createBitmap(w, h, Config.ARGB_8888);

        //OverlayView trackingOverlay;
        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        //cropSize, cropSize,
                        w, h,
                        0, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        //TODO my stuff, small bitmap
        croppedBitmap2 = Bitmap.createBitmap(w2, h2, Config.ARGB_8888);
    }

    @Override
    protected void processImage() {

        // Skip requests while computing detection
        if (computingDetection) return;

        // Start detection process
        computingDetection = true;

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);

        runInBackground(() -> {
            runOnUiThread(() -> setPredictionView("Performing recognition..."));

            // returns list sorted by confidence
            final List<Classifier.Recognition> detected_boxes = detector.recognizeImage(croppedBitmap);
            final Classifier.Recognition detected_box;

            // nothing detected
            if (detected_boxes.isEmpty() || detected_boxes.get(0).getConfidence() < MINIMUM_CONFIDENCE_TF_OD_API) {
                runOnUiThread(() -> setPredictionView("Recognized: Nothing"));
                computingDetection = false;
                return;
            } else {
                // map box location
                detected_box = detected_boxes.get(0);
                RectF location = detected_box.getLocation();
                cropToFrameTransform.mapRect(location);
                detected_box.setLocation(location);
            }

            cropDetectedBox(detected_box);
            final List<Classifier.Recognition> detected_digits = detector2.recognizeImage(croppedBitmap2);




            /************** Sort numbers by Position *****************/

            List<Classifier.Recognition> res = new ArrayList<>();
            for (final Classifier.Recognition result : detected_digits) {
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
            computingDetection = false;
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

    private void cropDetectedBox(Classifier.Recognition detected_box) {
        Rect rect = new Rect((int) detected_box.getLocation().left,
                (int) detected_box.getLocation().top,
                (int) detected_box.getLocation().right,
                (int) detected_box.getLocation().bottom);

        expandToSquare(rect);

        //  Create our resulting bitmap
        Bitmap tagBitmap = Bitmap.createBitmap(rect.right - rect.left, rect.bottom - rect.top, Bitmap.Config.ARGB_8888);
        //  Draw source bitmap into resulting bitmap
        new Canvas(tagBitmap).drawBitmap(rgbFrameBitmap, -rect.left, -rect.top, null);

        // rotation + 90 caused by portrait mode
        frameToCropTransform2 = ImageUtils.getTransformationMatrix(
                tagBitmap.getWidth(), tagBitmap.getHeight(), w2, h2,
                getOrientationDegrees() + 90, MAINTAIN_ASPECT);
        frameToCropTransform2.invert(new Matrix());

        // draw transformed bitmap onto croppedBitmap
        new Canvas(croppedBitmap2).drawBitmap(tagBitmap, frameToCropTransform2, null);
    }

    private void expandToSquare(Rect r) {
        int delta = (r.bottom - r.top) - (r.right - r.left);
        int upperBound = rgbFrameBitmap.getWidth();
        int min = r.left;
        int max = r.right;

        // height smaller than width
        if (delta < 0) {
            // TODO maybe change position detection, tag is rotated
            upperBound = rgbFrameBitmap.getHeight();
            delta = -delta;
            min = r.top;
            max = r.bottom;
        }

        if (max + delta < upperBound)
            max += delta;
        else {
            delta -= upperBound - max;
            max = upperBound;
            if (min - delta > 0)
                min -= delta;
            else
                min = 0;
        }

        if (upperBound == rgbFrameBitmap.getHeight()) {
            r.top = min;
            r.bottom = max;
        } else {
            r.left = min;
            r.right = max;
        }
    }
}
