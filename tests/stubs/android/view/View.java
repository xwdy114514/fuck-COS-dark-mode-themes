package android.view;
public class View { public static final int VISIBLE=0, GONE=8; private Object tag; private int visibility=VISIBLE; public Object getTag(){return tag;} public void setTag(Object t){tag=t;} public int getVisibility(){return visibility;} public void setVisibility(int v){visibility=v;} }
