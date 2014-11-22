package com.siyeh.igtest.j2me.private_member_access_between_outer_and_inner_class;

public class PrivateMemberAccessBetweenOuterAndInnerClass {
    private String caption = "Button";

    private void initialize() {
        System.out.println(caption);
        Object btn = new Object() {
            public void foo() {
                System.out.println(<warning descr="Access to private member of class 'PrivateMemberAccessBetweenOuterAndInnerClass'">caption</warning>);
            }
        };
    }
}
