package com.hnp.backendofflinefirst.util;

/**
 * Gregorian ↔ Jalali (Persian) calendar conversion (algorithm by Kazimierz M. Borkowski).
 */
final class JalaliConverter {

    private JalaliConverter() {}

    record JalaliDate(int year, int month, int day) {}

    static JalaliDate fromGregorian(int gy, int gm, int gd) {
        int[] gDaysInMonth = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        int jy;
        if (gy > 1600) {
            jy = 979;
            gy -= 1600;
        } else {
            jy = 0;
            gy -= 621;
        }
        int gy2 = (gm > 2) ? (gy + 1) : gy;
        int days = (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) - 80 + gd;
        for (int i = 1; i < gm; i++) {
            days += gDaysInMonth[i];
        }
        jy += 33 * (days / 12053);
        days %= 12053;
        jy += 4 * (days / 1461);
        days %= 1461;
        if (days > 365) {
            jy += (days - 1) / 365;
            days = (days - 1) % 365;
        }
        int jm;
        int jd;
        if (days < 186) {
            jm = 1 + days / 31;
            jd = 1 + days % 31;
        } else {
            jm = 7 + (days - 186) / 30;
            jd = 1 + (days - 186) % 30;
        }
        return new JalaliDate(jy, jm, jd);
    }
}
