import java.util.*;


class Simple {
  public static void main(String[] args) {
    class O {}

    Map<O, String> someData = new HashMap<O, String>();
    Set<String> set = new HashSet<String>();
    Set<O> setO = new HashSet<O>();

    set.removeAll(<warning descr="'Set<String>' may not contain objects of type 'O'">someData.keySet()</warning>);
    setO.removeAll(someData.keySet());

  }
}