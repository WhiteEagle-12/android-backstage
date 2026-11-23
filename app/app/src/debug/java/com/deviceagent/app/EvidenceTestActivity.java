package com.deviceagent.app;
/** Isolated input target for on-device release checks. Never enters the launcher. */
public final class EvidenceTestActivity extends android.app.Activity {
 @Override public void onCreate(android.os.Bundle state) {
  super.onCreate(state);
  android.widget.LinearLayout layout=new android.widget.LinearLayout(this);layout.setOrientation(android.widget.LinearLayout.VERTICAL);layout.setPadding(32,32,32,32);
  android.widget.TextView status=new android.widget.TextView(this);status.setText("Ready");status.setTextSize(24);layout.addView(status);
  android.widget.Button button=new android.widget.Button(this);button.setText("Check action");layout.addView(button);
  button.setOnClickListener(v->status.setText("Action observed"));
  android.widget.EditText input=new android.widget.EditText(this);input.setId(android.R.id.edit);input.setHint("Test text");input.setContentDescription("Test text");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);layout.addView(input);
  android.widget.Button select=new android.widget.Button(this);select.setText("Select middle");layout.addView(select);
  select.setOnClickListener(v->{input.setText("left OLD right");input.requestFocus();input.setSelection(5,8);});
  android.widget.Button blur=new android.widget.Button(this);blur.setText("Clear focus");blur.setFocusableInTouchMode(true);layout.addView(blur);blur.setOnClickListener(v->blur.requestFocus());
  android.widget.TextView instructions=new android.widget.TextView(this);instructions.setText("Tap Stop to pause");layout.addView(instructions);
  android.widget.Button popup=new android.widget.Button(this);popup.setText("Open stop popup");layout.addView(popup);
  popup.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setTitle("Shuttle stop").setMessage("Stop 7").setPositiveButton("Stop",(dialog,which)->status.setText("Stopped")).show());
  setContentView(layout);
 }
}
