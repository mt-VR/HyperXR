-- STILLSHOT level data. pillars: {x, z, w, d}; guns: {x, y, z}; enemies: {x, z, kind}
return {
  {
    name = "Atrium",
    pillars = { { -3, -5 }, { 3, -5 }, { 0, -9, 2, 0.8 } },
    tables = { { 0, -0.9 } },
    guns = { { 0, 0.98, -0.9 }, { 0.3, 0.98, -0.9 } },
    enemies = { { -2, -10, "walker" }, { 2.5, -11, "walker" } },
  },
  {
    name = "Corridor",
    pillars = { { -2, -3 }, { 2, -3 }, { -2, -7 }, { 2, -7 }, { -2, -11 }, { 2, -11 } },
    tables = { { -0.6, -0.9 } },
    guns = { { -0.6, 0.98, -0.9 } },
    enemies = { { -1, -12, "walker" }, { 1, -13, "runner" }, { 0, -8, "brawler" } },
  },
  {
    name = "Crossfire",
    pillars = { { -5, -2 }, { 5, -2 }, { -5, -8 }, { 5, -8 }, { 0, -6, 3, 0.6 } },
    tables = { { 0, -0.9 }, { -1.2, 0.6 } },
    guns = { { 0, 0.98, -0.9 }, { -1.2, 0.98, 0.6 }, { 0.3, 0.98, -0.9 } },
    enemies = { { -8, -6, "walker" }, { 8, -6, "walker" }, { 0, -12, "runner" }, { -3, -12, "brawler" }, { 4, 6, "walker" } },
  },
}
