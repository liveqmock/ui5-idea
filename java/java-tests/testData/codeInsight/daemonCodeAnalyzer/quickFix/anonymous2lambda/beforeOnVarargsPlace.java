// "Replace with lambda" "true"
class Test2 {

  void f(Runnable... rs){}
  {
    f(null, new Run<caret>nable() {
      @Override
      public void run() {

      }
    });
  }
}
