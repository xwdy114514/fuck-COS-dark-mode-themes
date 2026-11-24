package android.view;
public class ViewGroup extends View { public int getChildCount(){return 0;} public View getChildAt(int i){throw new IndexOutOfBoundsException();} }
