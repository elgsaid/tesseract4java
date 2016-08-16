package de.vorb.tesseract.gui.work;

import de.vorb.tesseract.gui.controller.TesseractController;
import de.vorb.tesseract.tools.recognition.RecognitionProducer;
import de.vorb.tesseract.util.feat.Feature3D;

import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.lept;
import org.bytedeco.javacpp.tesseract;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

public class PageRecognitionProducer extends RecognitionProducer {
    private final Path tessdataDir;
    private Optional<lept.PIX> lastPix = Optional.empty();

    private final TesseractController controller;
    private final HashMap<String, String> variables = new HashMap<>();

    public PageRecognitionProducer(TesseractController controller,
            Path tessdataDir, String trainingFile) {
        super(trainingFile);

        this.controller = controller;
        this.tessdataDir = tessdataDir;

        // save choices for choice iterator
        variables.put("save_blob_choices", "T");

        // heavy noise reduction
        // variables.put("textord_heavy_nr", "T");

        // language_model_penalty_non_dict_word
        variables.put("language_model_penalty_non_dict_word", "0.3");

        // blacklist doesn't work
        // variables.put("tessedit_char_blacklist", "=§«°·»¼ÃÆØå¼½æàâèéøɔ$");
    }

    @Override
    public void init() throws IOException {
        setHandle(tesseract.TessBaseAPICreate());

        reset();
    }

    @Override
    public void reset() throws IOException {
        // init LibTess with data path, language and OCR engine mode
        tesseract.TessBaseAPIInit2(getHandle(),
                tessdataDir.toString(),
                getTrainingFile(),
                tesseract.OEM_DEFAULT);

        // set page segmentation mode
        tesseract.TessBaseAPISetPageSegMode(getHandle(), tesseract.PSM_AUTO);

        // set variables
        for (Entry<String, String> var : variables.entrySet()) {
            tesseract.TessBaseAPISetVariable(getHandle(), var.getKey(), var.getValue());
        }
    }

    @Override
    public void close() throws IOException {
        tesseract.TessBaseAPIDelete(getHandle());
    }

    public void loadImage(Path imageFile) {
        if (lastPix.isPresent()) {
            // destroy old pix
            lept.pixDestroy(new PointerPointer(lastPix.get()));
        }

        final lept.PIX pix = lept.pixRead(imageFile.toString());

        tesseract.TessBaseAPISetImage2(getHandle(), pix);

        lastPix = Optional.of(pix);
    }

    public Optional<lept.PIX> getImage() {
        return lastPix;
    }

    public Optional<lept.PIX> getThresholdedImage() {
        return Optional.ofNullable(tesseract.TessBaseAPIGetThresholdedImage(getHandle()));
    }

    public List<Feature3D> getFeaturesForSymbol(BufferedImage symbol) {
        if (!lastPix.isPresent()) {
            return new LinkedList<>();
        }

        final int padding = 5;
        // draw a 5px white padding around the symbol
        final BufferedImage symbWithPadding = new BufferedImage(
                symbol.getWidth() + padding + padding,
                symbol.getHeight() + padding + padding,
                BufferedImage.TYPE_BYTE_BINARY);

        // draw the symbol on the new image
        final Graphics2D g2d = symbWithPadding.createGraphics();
        g2d.setBackground(Color.WHITE);
        g2d.clearRect(0, 0, symbWithPadding.getWidth(),
                symbWithPadding.getHeight());
        g2d.drawImage(symbol, padding, padding, null);
        g2d.dispose();

        // FIXME
        if (!controller.getProjectModel().isPresent()) {
            return Collections.emptyList();
        }

        final String symbolFile = controller.getProjectModel().get().getProjectDir().resolve(
                "symbol.png").toString();
        try {
            ImageIO.write(symbWithPadding, "PNG", new File(symbolFile));
        } catch (IOException e) {
            e.printStackTrace();
            return new LinkedList<>();
        }

        final lept.PIX pixSymb = lept.pixRead(symbolFile);

        final tesseract.TBLOB blob = tesseract.TessMakeTBLOB(pixSymb);
        lept.pixDestroy(new PointerPointer(pixSymb));

        final IntPointer numFeatures = new IntPointer();

        final IntPointer outlineIndexes = new IntPointer().capacity(512);

        final tesseract.INT_FEATURE_STRUCT intFeatures = new tesseract.INT_FEATURE_STRUCT().capacity(512);
        tesseract.TessBaseAPIGetFeaturesForBlob(getHandle(), blob, intFeatures, numFeatures, outlineIndexes);

        // make a list of Features3D
        final ArrayList<Feature3D> featureList = new ArrayList<>(numFeatures.get());

        final ByteBuffer features = intFeatures.asByteBuffer();

        for (int i = 0; i < numFeatures.get(); i++) {
            final int x = features.get(i * 4) & 0xFF;
            final int y = features.get(i * 4 + 1) & 0xFF;
            final int theta = features.get(i * 4 + 2) & 0xFF;
            final byte cpMisses = features.get(i * 4 + 3);
            final int outlineIndex = outlineIndexes.get(i);

            final Feature3D feat = new Feature3D(x, y, theta, cpMisses, outlineIndex);

            featureList.add(feat);
        }

        return featureList;
    }

    public void setVariable(String key, String value) {
        variables.put(key, value);
    }
}
