package com.deviceagent.app;

/** Private synthetic controls for release tests. */
@android.annotation.SuppressLint("SetTextI18n")
public final class SemanticTestActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        var layout=new android.widget.LinearLayout(this);layout.setOrientation(android.widget.LinearLayout.VERTICAL);layout.setPadding(24,24,24,24);
        var status=new android.widget.TextView(this);status.setText("Ready");status.setTextSize(24);layout.addView(status);
        var row=new android.widget.LinearLayout(this);row.setPadding(8,16,8,16);
        var title=new android.widget.TextView(this);title.setText("Test switch");row.addView(title,new android.widget.LinearLayout.LayoutParams(0,-2,1));
        var toggle=new android.widget.Switch(this);row.addView(toggle);row.setOnClickListener(v->toggle.toggle());layout.addView(row);
        var radioRow=new android.widget.LinearLayout(this);radioRow.setPadding(8,16,8,16);
        var radioTitle=new android.widget.TextView(this);radioTitle.setText("Test radio");radioRow.addView(radioTitle,new android.widget.LinearLayout.LayoutParams(0,-2,1));
        var radio=new android.widget.RadioButton(this);radio.setClickable(false);radioRow.addView(radio);radioRow.setOnClickListener(v->radio.setChecked(!radio.isChecked()));layout.addView(radioRow);
        var delayed=new android.widget.Button(this);delayed.setAllCaps(false);delayed.setText("Delayed action");layout.addView(delayed);
        int[] count={0};delayed.setOnClickListener(v->{int n=++count[0];status.setText("Pending "+n);status.postDelayed(()->status.setText("Completed "+n),800);});
        var held=new android.widget.Button(this);held.setAllCaps(false);held.setText("Long press action");layout.addView(held);
        held.setOnClickListener(v->status.setText("Short press"));held.setOnLongClickListener(v->{status.setText("Long press observed");return true;});
        var input=new android.widget.EditText(this);input.setHint("Test text");input.setContentDescription("Test text");layout.addView(input);
        setContentView(layout);
    }
}
