package ru.depositcalc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Рисует экран приложения в PNG — замена скриншотам на эмуляторе, который
 * требует аппаратной виртуализации. Файлы складываются в build/screenshots.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenshotTest {

    private static final File DIR = new File("build/screenshots");

    private void save(View root, String name) throws Exception {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = Math.max(root.getWidth(), root.getMeasuredWidth());
        int height = Math.max(root.getHeight(), root.getMeasuredHeight());
        root.layout(0, 0, width, height);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));

        DIR.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(DIR, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        System.out.println("скриншот: " + new File(DIR, name).getAbsolutePath()
                + " (" + width + "×" + height + ")");
    }

    @Test
    public void снимкиЭкрана() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = activity.getWindow().getDecorView();

        save(root, "1-conditions.png");

        ((EditText) activity.findViewById(R.id.amount)).setText("50000000");
        ((EditText) activity.findViewById(R.id.rate)).setText("24");
        ((EditText) activity.findViewById(R.id.term)).setText("12");
        ((EditText) activity.findViewById(R.id.topup)).setText("2000000");
        activity.findViewById(R.id.calculate).performClick();

        save(root, "2-result.png");
    }
}
