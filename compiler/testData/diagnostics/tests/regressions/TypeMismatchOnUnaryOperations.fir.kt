fun main() {
    val a : Int? = null;
    var v = 1
    val b : String = <!INITIALIZER_TYPE_MISMATCH!>v<!>;
    val f : String = <!INITIALIZER_TYPE_MISMATCH!>a!!<!>;
    val g : String = v++;
    val g1 : String = ++v;
    val h : String = v--;
    val h1 : String = --v;
    val i : String = <!INITIALIZER_TYPE_MISMATCH, TYPE_MISMATCH!>!true<!>;
    val j : String = <!INITIALIZER_TYPE_MISMATCH!><!REDUNDANT_LABEL_WARNING!>foo@<!> true<!>;
    val k : String = <!INITIALIZER_TYPE_MISMATCH!><!REDUNDANT_LABEL_WARNING!>foo@<!> <!REDUNDANT_LABEL_WARNING!>bar@<!> true<!>;
    val l : String = <!INITIALIZER_TYPE_MISMATCH!>-1<!>;
    val m : String = <!INITIALIZER_TYPE_MISMATCH!>+1<!>;
}
