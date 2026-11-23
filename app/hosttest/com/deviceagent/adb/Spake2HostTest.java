package com.deviceagent.adb;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Host self-test 2: SPAKE2 A/B agree; M/N encode round-trip; known-answer from a Python oracle
 * that follows BoringSSL {@code spake25519.cc} (no fixed vectors in {@code spake25519_test.cc}).
 */
public final class Spake2HostTest {
    public static void main(String[] args) throws Exception {
        run(System.out);
        System.out.println("Spake2HostTest OK");
    }

    static void run(PrintStream out) throws Exception {
        byte[] mEnc = Ed25519.encode(Ed25519.M);
        byte[] nEnc = Ed25519.encode(Ed25519.N);
        check(Arrays.equals(mEnc, Ed25519.M_ENCODED), "M encode != BoringSSL encoded constant");
        check(Arrays.equals(nEnc, Ed25519.N_ENCODED), "N encode != BoringSSL encoded constant");
        check(Arrays.equals(Ed25519.encode(Ed25519.decode(Ed25519.M_ENCODED)), Ed25519.M_ENCODED), "M round-trip");
        check(Arrays.equals(Ed25519.encode(Ed25519.decode(Ed25519.N_ENCODED)), Ed25519.N_ENCODED), "N round-trip");

        // Decimal coordinates from BoringSSL comments.
        check(Ed25519.M.x.toString().equals(
                "31406539342727633121250288103050113562375374900226415211311216773867585644232"),
                "M.x");
        check(Ed25519.M.y.toString().equals(
                "21177308356423958466833845032658859666296341766942662650232962324899758529114"),
                "M.y");
        check(Ed25519.N.x.toString().equals(
                "49918732221787544735331783592030787422991506689877079631459872391322455579424"),
                "N.x");
        check(Ed25519.N.y.toString().equals(
                "54629554431565467720832445949441049581317094546788069926228343916274969994000"),
                "N.y");

        byte[] password = "password".getBytes(StandardCharsets.US_ASCII);
        Spake2 alice = new Spake2(Spake2.Role.ALICE, Spake2.CLIENT_NAME, Spake2.SERVER_NAME);
        Spake2 bob = new Spake2(Spake2.Role.BOB, Spake2.SERVER_NAME, Spake2.CLIENT_NAME);
        byte[] aMsg = alice.generateMsg(password);
        byte[] bMsg = bob.generateMsg(password);
        check(aMsg.length == 32, "Alice msg len " + aMsg.length);
        check(bMsg.length == 32, "Bob msg len " + bMsg.length);
        byte[] aKey = alice.processMsg(bMsg);
        byte[] bKey = bob.processMsg(aMsg);
        check(aKey.length == 64, "key len");
        check(Arrays.equals(aKey, bKey), "A/B keys differ");

        Spake2 alice2 = new Spake2(Spake2.Role.ALICE, Spake2.CLIENT_NAME, Spake2.SERVER_NAME);
        Spake2 bob2 = new Spake2(Spake2.Role.BOB, Spake2.SERVER_NAME, Spake2.CLIENT_NAME);
        byte[] wrong = "passworx".getBytes(StandardCharsets.US_ASCII);
        byte[] aMsg2 = alice2.generateMsg(password);
        byte[] bMsg2 = bob2.generateMsg(wrong);
        byte[] aKey2 = alice2.processMsg(bMsg2);
        byte[] bKey2 = bob2.processMsg(aMsg2);
        check(!Arrays.equals(aKey2, bKey2), "different passwords produced same key");

        // Known-answer: password = "123456" || 64 zero bytes, privA=0..63, privB=64..127.
        byte[] katPwd = new byte[6 + 64];
        System.arraycopy("123456".getBytes(StandardCharsets.US_ASCII), 0, katPwd, 0, 6);
        byte[] privA = new byte[64];
        byte[] privB = new byte[64];
        for (int i = 0; i < 64; i++) {
            privA[i] = (byte) i;
            privB[i] = (byte) (64 + i);
        }
        Spake2 katA = Spake2.adbClient();
        Spake2 katB = Spake2.adbServer();
        byte[] katAMsg = katA.generateMsg(katPwd, privA);
        byte[] katBMsg = katB.generateMsg(katPwd, privB);
        check(Bytes.toHex(katAMsg).equals(
                "0d0bb577b68b485499755dceecb4916120f1fa766a55ad733ec7be6e3a687853"),
                "KAT Alice msg: " + Bytes.toHex(katAMsg));
        check(Bytes.toHex(katBMsg).equals(
                "8d9145f467364c1c4457e4c2f1d6d501f25fad15a90bc06f120f4cb994baf575"),
                "KAT Bob msg: " + Bytes.toHex(katBMsg));
        byte[] katKey = katA.processMsg(katBMsg);
        check(Arrays.equals(katKey, katB.processMsg(katAMsg)), "KAT A/B");
        check(Bytes.toHex(katKey).equals(
                "e8e6d9a199b78e8bcc96d61846bce28a3b45e616c5e8c957fab3a46ef20da76d"
                        + "790690ee2c22aaae1c2ff6fa1f25b37b65d68a76f84accd5a098f7ef02af6f12"),
                "KAT key: " + Bytes.toHex(katKey));

        // sc_reduce of 64 0xff must be < L
        byte[] ff = new byte[64];
        Arrays.fill(ff, (byte) 0xff);
        byte[] red = Ed25519.scReduce(ff);
        java.math.BigInteger r = Ed25519.fromLe(red);
        check(r.signum() >= 0 && r.compareTo(Ed25519.L) < 0, "sc_reduce not in [0,L)");
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
