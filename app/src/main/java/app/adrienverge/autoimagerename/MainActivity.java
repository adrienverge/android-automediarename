/*
 * Copyright 2022 Adrien Vergé
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package app.adrienverge.autoimagerename;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.text.method.ScrollingMovementMethod;
import java.util.Date;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.work.Constraints;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

public class MainActivity extends AppCompatActivity {

  private static final String TAG = "autoimagerename";
  private static final int SELECT_DIR = 1;
  private Config config;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_main);
    Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    config = Config.getInstance(this);
    // If this is the first use of the app, let's set the minimum timestamp, to
    // avoid touching files older than 1 day.
    if (config.getFiltersMinimumTimestamp() == 0) {
      Calendar calendar = Calendar.getInstance();
      calendar.add(Calendar.DAY_OF_MONTH, -1);
      config.setFiltersMinimumTimestamp(calendar.getTimeInMillis());
      config.save();
    }

    findViewById(R.id.selectImagesDirButton).setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                Environment.DIRECTORY_DCIM);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, SELECT_DIR);
          }
        });

    if (config.getFiltersDirectory() == null) {
      ((TextView) findViewById(R.id.selectImagesDirText))
      .setText("Please select the directory where images are.");
    } else {
      ((TextView) findViewById(R.id.selectImagesDirText))
      .setText("Selected directory: " + config.getFiltersDirectory());
    }

    ((TextView) findViewById(R.id.filterLastModifiedDateText))
    .setText("Files modified before this date will not be touched: " +
        Logger.toISO8601(new Date(config.getFiltersMinimumTimestamp())));

    findViewById(R.id.lastModifiedDateButton).setOnClickListener(
      new View.OnClickListener() {
        @Override
        public void onClick(View view) {
          final Calendar calendar = Calendar.getInstance();
          calendar.setTimeInMillis(config.getFiltersMinimumTimestamp());
          int day = calendar.get(Calendar.DAY_OF_MONTH);
          int month = calendar.get(Calendar.MONTH);
          int year = calendar.get(Calendar.YEAR);
          DatePickerDialog picker = new DatePickerDialog(MainActivity.this,
              new DatePickerDialog.OnDateSetListener() {
                @Override
                public void onDateSet(DatePicker view, int year, int
                    month, int day) {
                  calendar.set(year, month, day);
                  calendar.set(Calendar.HOUR_OF_DAY, 0);
                  calendar.set(Calendar.MINUTE, 0);
                  calendar.set(Calendar.SECOND, 0);
                  config.setFiltersMinimumTimestamp(calendar.getTimeInMillis());
                  config.save();
                  ((TextView) findViewById(R.id.filterLastModifiedDateText))
                    .setText("Files modified before this date will not be touched: " +
                        Logger.toISO8601(new Date(config.getFiltersMinimumTimestamp())));
                }
              }, year, month, day);
          picker.show();
        }
    });

    CheckBox keepBackupCheckBox = findViewById(R.id.keepBackupCheckBox);
    keepBackupCheckBox.setChecked(config.getCompressionKeepBackup());
    keepBackupCheckBox.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        config.setCompressionKeepBackup(((CheckBox) view).isChecked());
        config.save();
      }
    });

    findViewById(R.id.simpleWorkButton).setOnClickListener(
      new View.OnClickListener() {
        @Override
        public void onClick(View view) {
          OneTimeWorkRequest oneTimeRequest =
            new OneTimeWorkRequest.Builder(PeriodicWorker.class)
            .build();
          WorkManager.getInstance().enqueue(oneTimeRequest);
        }
    });

    findViewById(R.id.periodicWorkButton).setOnClickListener(
      new View.OnClickListener() {
        @Override
        public void onClick(View view) {
          // Cancel any previously-scheduled works.
          WorkManager.getInstance().cancelAllWork();

          // Android jetpack periodic work request has a minimum period length
          // of 15 minutes. Specifying a lower value does not work.
          int seconds = config.getPeriodicWorkPeriod();
          PeriodicWorkRequest periodicWorkRequest =
            new PeriodicWorkRequest.Builder(
                PeriodicWorker.class, seconds, TimeUnit.SECONDS)
            .setConstraints(
              new Constraints.Builder()
              .setRequiresBatteryNotLow(true)
              .build()
            ).build();
          WorkManager.getInstance().enqueue(periodicWorkRequest);
        }
    });

    ((TextView) findViewById(R.id.requestIgnoreBatteryOptimizationsText))
    .setText("To run periodically in background, the app needs authorization " +
        "to run in background.");

    findViewById(R.id.requestIgnoreBatteryOptimizationsButton).setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            PowerManager pm =
              (PowerManager) getSystemService(Context.POWER_SERVICE);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
          }
        });

    int seconds = config.getPeriodicWorkPeriod();
    SeekBar periodSeekBar = findViewById(R.id.periodSeekBar);
    periodSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        String humanFriendlyPeriod;
        if (progress == 0) {
          humanFriendlyPeriod = "15 minutes";
          config.setPeriodicWorkPeriod(900);
        } else if (progress == 1) {
          humanFriendlyPeriod = "60 minutes";
          config.setPeriodicWorkPeriod(3600);
        } else if (progress == 2) {
          humanFriendlyPeriod = "6 hours";
          config.setPeriodicWorkPeriod(21600);
        } else {
          humanFriendlyPeriod = "24 hours";
          config.setPeriodicWorkPeriod(86400);
        }
        config.save();
        ((TextView) findViewById(R.id.periodInfoText))
        .setText("The app will run every " + humanFriendlyPeriod + ".");
      }
      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {}
      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {}
    });
    if (seconds == 900) {
      periodSeekBar.setProgress(0);
    } else if (seconds == 3600) {
      periodSeekBar.setProgress(1);
    } else if (seconds == 21600) {
      periodSeekBar.setProgress(2);
    } else {
      periodSeekBar.setProgress(3);
    }

    new Logger(this).addLine("Launched activity");

    TextView mTextView = findViewById(R.id.log);
    mTextView.setMovementMethod(new ScrollingMovementMethod());
    mTextView.setText(new Logger(this).read());
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode,
      Intent resultData) {
    if (requestCode == SELECT_DIR && resultCode == Activity.RESULT_OK) {
      if (resultData != null) {
        Uri uri = resultData.getData();

        Log.i(TAG, "User chose directory: " + uri);

        final int takeFlags = resultData.getFlags()
          & (Intent.FLAG_GRANT_READ_URI_PERMISSION
              | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(uri, takeFlags);

        config.setFiltersDirectory(uri.toString());
        config.save();
      }
    }
  }
}
