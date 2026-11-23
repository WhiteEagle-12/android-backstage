package com.androidbackstage.qa;

import android.app.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** Disposable app for window selection and explicitly authorized main-screen checks. */
public final class WindowFixture extends Activity {
    private PopupWindow panel;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        var root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,32,32,32);
        var title=new TextView(this);title.setText("Window controls");title.setTextSize(24);root.addView(title);
        var note=new EditText(this);note.setHint("Note");root.addView(note);
        add(root,"Show dialog",()->new AlertDialog.Builder(this).setTitle("Dialog").setPositiveButton("Confirm",(d,w)->getSharedPreferences("windows",0).edit().putBoolean("confirmed",true).apply()).show());
        add(root,"Show panel",()->{
            var button=new Button(this);button.setText("Panel action");button.setOnClickListener(v->{getSharedPreferences("windows",0).edit().putBoolean("panelAction",true).apply();panel.dismiss();});
            panel=new PopupWindow(button,500,180,false);panel.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE));panel.setElevation(12);panel.showAtLocation(root,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL,0,160);
        });
        add(root,"Open keyboard",()->{note.requestFocus();getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(note,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
        setContentView(root);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        getSharedPreferences("windows",0).edit().clear().putInt("display",getDisplay().getDisplayId()).apply();
    }
    private void add(LinearLayout root,String label,Runnable action){var button=new Button(this);button.setText(label);button.setOnClickListener(v->action.run());root.addView(button);}
    @Override public void onDestroy(){if(panel!=null)panel.dismiss();super.onDestroy();}
}
