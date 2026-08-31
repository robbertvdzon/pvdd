import 'package:flutter/material.dart';

abstract final class PvddColors {
  static const ink = Color(0xff102a2c);
  static const sidebar = Color(0xff102a2c);
  static const sidebarSelected = Color(0xff214345);
  static const primary = Color(0xff3f8f77);
  static const mint = Color(0xff22c99b);
  static const background = Color(0xfff3f5f1);
  static const surface = Color(0xfffffefd);
  static const outline = Color(0xffdbe3dd);
}

ThemeData pvddTheme() {
  final scheme = ColorScheme.fromSeed(
    seedColor: PvddColors.primary,
    surface: PvddColors.surface,
  ).copyWith(primary: PvddColors.primary, secondary: PvddColors.mint);
  final base = ThemeData(colorScheme: scheme, useMaterial3: true);
  return base.copyWith(
    scaffoldBackgroundColor: PvddColors.background,
    cardTheme: const CardThemeData(
      color: PvddColors.surface,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(16)),
        side: BorderSide(color: PvddColors.outline),
      ),
    ),
    textTheme: base.textTheme.copyWith(
      headlineMedium: base.textTheme.headlineMedium?.copyWith(
        color: PvddColors.ink,
        fontWeight: FontWeight.w800,
      ),
    ),
  );
}
