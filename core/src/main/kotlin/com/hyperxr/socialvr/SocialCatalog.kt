package com.hyperxr.socialvr

/** How an avatar presents (the three shelves of the avatar picker). */
enum class AvatarStyle(val label: String) { FEMININE("Feminino"), MASCULINE("Masculino"), NEUTRAL("Não binário") }

/** A downloadable avatar: a VRM (glTF 2.0) file. */
data class SocialAvatar(val number: String, val name: String, val style: AvatarStyle, val url: String) {
    val id: String get() = "pm$number"
}

/**
 * A map put together by HyperMagic from one CC0 asset pack: [ground] pieces in the middle, the
 * [props] around a plaza ([MapLayout]). Pieces are GLB files of the pack, by name.
 */
data class SocialMap(val id: String, val title: String, val pack: String, val ground: List<String>, val props: List<String>) {
    fun url(piece: String) = "${SocialCatalog.MAP_BASE}/$pack/$piece.glb"
    val pieces: List<String> get() = ground + props
}

/**
 * What the social game (Encontro) offers, all public domain (CC0):
 *  - avatars: Polygonal Mind's 100Avatars series (VRM), as listed by the Open Source Avatars
 *    registry (github.com/ToxSam/open-source-avatars), files on Arweave;
 *  - maps: pieces of Polygonal Mind's open-source environment packs, as GLB
 *    (github.com/ToxSam/cc0-models-Polygonal-Mind), arranged by HyperMagic.
 * Licences checked in both repositories (CC0 1.0), and inside each VRM (meta licenseName CC0).
 */
object SocialCatalog {
    const val MAP_BASE = "https://raw.githubusercontent.com/ToxSam/cc0-models-Polygonal-Mind/main/projects"
    const val LICENSE = "CC0 1.0 (domínio público), Polygonal Mind"

    val MAPS = listOf(
        SocialMap("praca-medieval", "Feira Medieval", "medieval-fair",
            ground = listOf("CenterPlatform", "Floor", "Path", "StageBackground"),
            props = listOf("Barrel", "Barrel_Beer_Mountain", "Beer", "BoardCutout", "Booth_Food01", "Booth_Pretzelgame", "Booth_Wearables", "Cart", "EntranceBoard", "Fair_Entry", "Fair_Flags_Line", "Lamp", "OutdoorBathroom", "Pants", "Roof", "Sausage", "SignPost", "SmallBarrel_Art", "SpeakerPost", "Tabern", "Table_Dessert", "Table_Dinner")),
        SocialMap("templo-lunar", "Templo do Ano Lunar", "lunar-year",
            ground = listOf("Floor01", "Floor02", "LunarYearTile", "RoomFloor"),
            props = listOf("ArchBanner", "Banner", "BellStructure", "Carpet01", "Column", "Cushion", "Dragon", "EntranceStairs", "Fence02", "InteriorLight02", "InteriorLight04", "InteriorLight06", "Lamp01", "Lamp03", "LampWreath", "OutterLamp", "Portal", "Rock01", "RoomDoor", "RoomRoofBeam", "Table01", "TerrainFloor")),
        SocialMap("casa-natal", "Casa de Natal", "christmas",
            ground = listOf(),
            props = listOf("Candle", "CandyCane", "ExteriorWall", "Fireplace", "InteriorDoor", "InteriorWall", "Lights01", "MiniTree", "Present01", "Present02", "PresentSackXmas01", "Shelving", "SockXmas", "StoreCartel", "Tank", "WoodBeam01", "WoodBeam03", "WoodColumn", "WoodDetails", "Wreath01", "Wreath03", "Wreaths05")),
        SocialMap("fabrica-polka", "Fábrica Trash Polka", "trash-polka",
            ground = listOf("InteriorFloor", "Terrain"),
            props = listOf("Door", "ExteriorDoor", "InfoPanel", "InteriorDoor", "InteriorWall", "InteriorWall02", "Kiosco", "Lateral01", "Light01", "Light02", "Light03", "Light05", "Light06", "Light07", "Light08", "PoapMachine", "Robot", "Rock", "Screen", "Structure02", "Structure03", "Tank")),
        SocialMap("estacao-trem", "Estação de Trem", "transit",
            ground = listOf("Dirt_Path_Art", "Platform_01_Art", "TowerStation_Ground_Art"),
            props = listOf("Bench_01", "Iron_Structure_01_Art", "Pipe_01_Art", "Plant_01_Art", "Plant_02_Art", "Rock_01", "TowerStation_Dirt_Art", "Tower_Ornamental_Fence_Art", "Tower_Station_01_Art", "Tower_Station_01_SponsorRings_Art", "Tower_Station_Elevator_Art", "Tower_Station_Fence_Art", "Tower_Station_Light_Art", "Tower_Station_Sign_Art", "Tower_Station_Telephone_Art", "Track_01_Tap_Art", "Train_01_Art", "Train_02_Art", "Train_Track_01")),
        SocialMap("aero-ilhas", "Ilhas Aéreas", "aero-system",
            ground = listOf("Aero_Ground_Hexagon_Art", "Aero_Ground_Hexagons_01_Art", "Aero_Ground_Hexagons_02_Art", "Aero_Station_Mini_Platform_Art"),
            props = listOf("Aero_Airship_01", "Aero_Door_01", "Aero_Lampost_01", "Aero_Station_01_Art", "Aero_Station_PinkRing_Art", "Aero_Station_Ring_Art", "Aero_Station_YellowRing_Art", "Floating_Island_01_Art", "Path_01_Art", "Terrain_Art", "Tree_01_Art")),
        SocialMap("palco-show", "Palco do Show", "avatar-show",
            ground = listOf("Stage_Floor", "Stage_Platform"),
            props = listOf("Arm_Chair", "Banana_Plant", "Carpet", "Curved_Pipe", "Extractor", "Jar", "Lamp_Wall", "Long_Chair", "Platea_Straight", "Poly_Plush", "Pot_Plant", "Sofa", "Spectator_Chair", "Stage_Decor", "Stage_Full_Structure", "Stage_Structure_Backstage", "Stage_Structure_Skylight", "Straight_Pipe", "Studio_Lamp", "Table", "TableLamp", "Table_Round_01")),
        SocialMap("tumba-deserto", "Tumba do Deserto", "tomb-chaser-1",
            ground = listOf("Layout_Floor01", "Layout_Floor02", "Layout_Floor03", "Layout_Floor04"),
            props = listOf("Column_Art", "Door_Art", "FireTorch02_Art", "FireTorch03_Art", "GodBastet_Art", "GodRa_Art", "InteriorEmbelisher02_Art", "Lance_Art", "Oasis_Art", "Obelisk_Art", "PlatformBeams_Art", "Podium_Art", "SandMount_Art", "TempleArch01_Art", "TempleArch02_Art", "TempleEmbelisher02_Art", "TempleEmbelisher03_Art", "TempleFloor02_Art", "TempleFloor03_Art", "Torch_Art", "Trap_Art", "Wall02_Art")),
        SocialMap("tumba-neon", "Templo Neon", "tomb-chaser-2",
            ground = listOf("Floor01_Art", "Floor02_Art", "GlitchFloor_Art", "TempleBase_Art"),
            props = listOf("Column01_Art", "Column02_Art", "Decoration01_Art", "Decoration02_Art", "ElectricPost01_Art", "Exit_Art", "Frame_Art", "Grass01_Art", "Mist01_Art", "Mist02_Art", "Rock01_Art", "Shrine_Art", "TempleBaseCorner_Art", "TempleBaseDetail02_Art", "TempleBaseStaircase_Art", "TempleBeam02_Art", "TempleRoof01Corner_Art", "TempleRoof01_Art", "TempleRoof02_Art", "TempleWall02_Art", "TempleWallCorner_Art", "WarningSign01_Art")),
        SocialMap("encruzilhada", "Encruzilhada de Cristal", "crystal-crossroads",
            ground = listOf("Dune_Sand", "Floor_Sand_Large", "Floor_Sand_Medium", "Floor_Sand_Small"),
            props = listOf("Arc", "Column_SmallBroken_01", "Crystal_ClusterSurrounded", "Crystal_Small_03", "Floor_Tiles_Large", "FlowerPot_Large_01", "FlowerPot_Narrow_01", "FlowerPot_Small_03", "Pattern_Cylinder", "PictureFrame_Regular", "Platform_StairsPlatform", "Platform_Stairs_02", "Platform_Step", "Roof_Hole_01", "SciFi_Battery_Capsule01", "SciFi_Bridge", "SciFi_GlassScreen", "SciFi_PlatformStairs_Support", "SciFi_Stairs_32", "Stairs", "Vase_Mid", "Wall_Broken_01")),
        SocialMap("cabine-retro", "Cabine Retrô", "cryptoavatars-retro-booth",
            ground = listOf("Floor", "Floor_Beam", "Floor_Platform", "Floor_Ring"),
            props = listOf("CA_Head", "Capsule_Avatar", "Capsule_Container", "Computer_Screen", "Computer_Structure", "Energy_Ball_Projector", "Japanese_Arch", "Japanese_Roof", "Pipe_02", "RetroComputerBooth", "Sidewalk", "Top_Beam", "Top_Ceiling", "Top_Ring", "Wall_01", "Wall_03", "Wall_Module_01", "Wall_Module_02", "Wall_Module_03", "Wall_Module_04", "Wall_Module_05", "Wall_Module_06")),
        SocialMap("galeria-grega", "Galeria Grega", "ca-world",
            ground = listOf("Floor_01", "Floor_02", "LAND", "Light_Floor_01"),
            props = listOf("AvatarBust_01", "Bench_02", "CAIO_Whale_01", "Carpet_03", "Column_Base_01", "Curtain_02", "Decor_02_Greek", "Door_02", "Launch_Building_Coon_Stairs", "Light_Rail_01", "Light_Stair_01", "Links_Frames_Big_PolygonalMind", "Pot_02", "Railing_02", "Rock_03", "Shelf_03_a", "Shelf_05_a", "Stairs_02", "Step_01_Corner", "Stool_01", "Wall_01_Door", "Wall_02_Corner")),
        SocialMap("parque-momus", "Parque Momus", "MomusPark",
            ground = listOf("Floating_Island_01_Art", "Floating_Island_02_Art", "Floating_Island_3_Art", "Floating_Island_4_Art"),
            props = listOf("Bench_01_Art", "Bush_01_Art", "Butterfly", "Flower_01_a", "Flower_02_b_Art", "Grass_01_a", "Log_01_Art", "Mushroom_01_Art", "Path_01_Art", "Path_04_Art", "Rock_03_Art", "Root_01_Art", "Shelter_Art", "Skydoor_Art", "Str_Amphitheater_01_Art", "Str_Ruins_01_Art", "Str_Ruins_05_Art", "Tree_01_Art", "Tree_04_Art", "Water_Fall_01_Art", "Water_plane_Art", "eyes_haunted_03_Art")),
        SocialMap("vaporwave", "Sala Vaporwave", "chromatic-chaos",
            ground = listOf("Floor_Vapor_01", "Floor_Vapor_02", "Terrain"),
            props = listOf("Building_Corner_01", "Building_Rect_01", "Building_Vapor_Ramp_02", "Column_Vapor_01", "Column_Vapor_02", "ComputerScreen_Open_Retro", "Computer_Retro", "David_Retro", "Frame_Gameboy_Retro_01", "Frame_Neon_Vapor_02", "Frame_Neon_Vapor_03", "Glass_Corner_Vapor", "MobilePhone_Retro", "Mouse_Retro", "Pot_Retro_02", "Pot_Retro_04", "Pot_Retro_05", "Pot_Vapor_02", "Pot_Vapor_04", "Pot_Vapor_05", "Screen_Retro_02", "Venus_01_Floating")),
        SocialMap("jardim-zen", "Jardim Hexagonal", "abm",
            ground = listOf("FloorPanel01_Art", "FloorPanel02_Art", "FloorPanel03_Art", "FloorPanel04_Art"),
            props = listOf("Altar01_Art", "Dome01_Art", "Dome03_Art", "DomeExtra01_Art", "EntranceDoor01_Art", "FloorPanel05_Art", "HexagonLargeVIP01_Art", "HexagonLargeVIP03_Art", "HexagonPlatformLarge01_Art", "HexagonPlatformRamp01_Art", "HoloBush01_Art", "HoloBush03_Art", "HoloTree01", "InfoPanel01_Art", "Petals01_Art", "Platform01_Art", "PlatformHolder01_Art", "Teleporter01_Art", "TeleporterVIP01_Art", "TerrainPath01_Art", "Tree01_Art", "VIPWall01_Art")),
        SocialMap("zoo-geometrico", "Zoológico Geométrico", "xyz",
            ground = listOf(),
            props = listOf("001_Triangulon_Art", "003_Hexabear_Art", "006_Whormbus_Art", "009_Trirectankey_Art", "011_Rectashark_Art", "014_Penturtlen_Art", "017_Sauris_Art", "020_Octozilla_Art", "022_Triplicoon_Art", "025_Snakis_Art", "028_Hexowl_Art", "030_Crabylon_Art", "033_Monkeylon_ The_Middle_Fighter_Art", "036_Crabylon_Art", "039_Mousylon_Art", "041_Beaveriangle_Art", "044_Orclygon_Art", "047_Peacolion_Art", "050_Binguilon_Art", "052_Owltron_Art", "055_Searlygon_Art", "058_Symbbit_Art")),
        SocialMap("torres-blockchain", "Torres de Código", "towers",
            ground = listOf("BlockChain_Floor_Art"),
            props = listOf("BlockChain_Bridge_Art", "BlockChain_Bridge_Fragment_Art", "BlockChain_CodeBush_Art", "BlockChain_Eth_Art", "BlockChain_Light_Art", "BlockChain_Roots_Art", "BlockChain_Step_Art", "BlockChain_Tower_02_Art", "BlockChain_Tower_Art", "Bridge_Nexus_Art", "ControlPoint_Art", "Garden_01_Art", "Garden_02_Art", "Garden_03_Art", "Garden_04_Art", "Garden_05_Art", "Garden_06_Art", "Garden_07_Art", "Garden_08_Art", "Garden_09_Art")),
        SocialMap("colonia-alien", "Colônia Alienígena", "towers",
            ground = listOf(),
            props = listOf("Colony_Alien_Sign_Art", "Colony_BridgeSection_Art", "Colony_Bridge_Fragment_Art", "Colony_Egg_Art", "Colony_Flower_Art", "Colony_MiniPC_Art", "Colony_Monolith_02_Art", "Colony_Monolith_Art", "Colony_Mushroom_Art", "Colony_Planet_02_Art", "Colony_Planet_Art", "Colony_Ring_Art", "Colony_Rocket_Art", "Colony_Shell_Art", "Colony_StairsPiece_Art", "Colony_Step_Art", "Colony_Tower_Art", "Colony_Tower_Bridge_Art", "Colony_UFO_Art")),
        SocialMap("jardim-avatares", "Jardim dos Avatares", "avatar-garden",
            ground = listOf("GroundPlatform01", "TerrainGrass01", "TerrainGround01", "TerrainGroundGrass01"),
            props = listOf("BasePalmTree01", "Bush01", "Bush03", "Bush05", "Bush08", "Cloud01", "Cloud03", "Dock01", "Flower03", "Flower05", "Flower07", "Grass02", "Grass04", "Mountain02", "Mountain04", "PalmTree03", "Stone01", "Stone03", "Stone05", "TerrainSand01", "Tree01", "Tree03")),
        SocialMap("ilha-portais", "Ilha dos Portais", "avatar-garden",
            ground = listOf("GroundPlatform01", "NewAvatarsPlatform01", "TerrainGrass01", "TerrainGround01"),
            props = listOf("AvatarPedestal01", "BlackHole01", "Brush01", "InformationStand01", "InformativePanel01", "PaintEasel01", "PaintEasel02", "PaintEasel03", "Palette01", "PlanetStardust01", "Portal01", "Portal02", "Portal03", "PortalStones01", "PortalThumbnail01", "PortalThumbnail02", "PortalThumbnail03", "TerrainGroundGrass01", "TerrainGroundRight01", "TerrainSand01", "TerrainWater01", "WorldGear01")),
    )
    val AVATARS = listOf(
        SocialAvatar("038", "Kate", AvatarStyle.FEMININE, "https://arweave.net/1q4IQwLQXJVS0JGSpeXlRdazmZYdwJbmLbTv7o0s5Y8"),
        SocialAvatar("050", "Samuela", AvatarStyle.FEMININE, "https://arweave.net/4VjBzmk3iDQS0-013pUMFpFYbKGNTL4qcQ-PVwADxk4"),
        SocialAvatar("052", "Jennifer", AvatarStyle.FEMININE, "https://arweave.net/LKp1uJLAZFmncdCNSZ8oopU7ZElXTvn4BmM4CUcFclc"),
        SocialAvatar("053", "Erika", AvatarStyle.FEMININE, "https://arweave.net/GZkfa0SNnrBWluRL_pXpakg7T3K3d4l87__wR4mD3UM"),
        SocialAvatar("054", "Lydia", AvatarStyle.FEMININE, "https://arweave.net/x48D7v037irPQYG7e0vZLDV1E3x5-KookbP9-vaXvYE"),
        SocialAvatar("056", "Olivia", AvatarStyle.FEMININE, "https://arweave.net/MgsNlTetzAoVEC6E-lswj65vp7StkOZXXd5OjjqzYZI"),
        SocialAvatar("057", "Rose", AvatarStyle.FEMININE, "https://arweave.net/Ea1KXujzJatQgCFSMzGOzp_UtHqB1pyia--U3AtkMAY"),
        SocialAvatar("226", "Eugenia", AvatarStyle.FEMININE, "https://arweave.net/saOexMViu7mqSeaXfQzNIPrKWQ0nqkSf-FpOQjZfBcU"),
        SocialAvatar("243", "Agnes", AvatarStyle.FEMININE, "https://arweave.net/c8mrbRq29sfQdovW1l_D2JYGOaCNF3JxTaUsmHTSNAg"),
        SocialAvatar("270", "Anna", AvatarStyle.FEMININE, "https://arweave.net/RqDioyluCcxDNHFPoXLUlbEQjhP1Fy9HtaGzLFVh0mE"),
        SocialAvatar("003", "Jimmy", AvatarStyle.MASCULINE, "https://arweave.net/mmPA7zxvHTEEmKEAnMPX7jnaT1MD4dqUj8mTPenSAiw"),
        SocialAvatar("004", "OldMoustache", AvatarStyle.MASCULINE, "https://arweave.net/li-q3WXvBik3A1aYyp7azx7Mwce3taHx6OSOE1pHdKk"),
        SocialAvatar("008", "Hugo", AvatarStyle.MASCULINE, "https://arweave.net/iYaEdMdq8faogyRdgF4plnZIq40oOERENie94XmEdvQ"),
        SocialAvatar("047", "David", AvatarStyle.MASCULINE, "https://arweave.net/H3cBhsOEoiQ8XZiwG31SyCUtiDewBZRccxIDztyHfSY"),
        SocialAvatar("069", "Kyle", AvatarStyle.MASCULINE, "https://arweave.net/0E5wEEVl5VGCcuGVQylq3dpC3TClLR3JhuhEyhYh7NQ"),
        SocialAvatar("070", "Robert", AvatarStyle.MASCULINE, "https://arweave.net/gwG7w4bY-A5c3R6A6GOz3xBCgbPvkFQmqPIDtvnNsYI"),
        SocialAvatar("072", "Mikel", AvatarStyle.MASCULINE, "https://arweave.net/-eJyDjujQRvakRImdvulg-1dKQkPwMeQv-55IbKqLh4"),
        SocialAvatar("079", "Chad", AvatarStyle.MASCULINE, "https://arweave.net/s15TxeRcxamOZ0qDfjME1Bl2Ku7Vs4IQs8RthpxYjOQ"),
        SocialAvatar("233", "Bruno", AvatarStyle.MASCULINE, "https://arweave.net/WR_xW_yGKwubTj-kdlyw5jNVx5m2ldMxwg_enz_Znbw"),
        SocialAvatar("232", "Vladi", AvatarStyle.MASCULINE, "https://arweave.net/RFKE0Snx7V32gjvV82GcXT9-R7rDySQaKWlf1a2oYUc"),
        SocialAvatar("002", "CoolAlien", AvatarStyle.NEUTRAL, "https://arweave.net/FB3g343NrNmQrr4V0191V93pbzOVwTiQWF3PEcL4MNg"),
        SocialAvatar("017", "Ro", AvatarStyle.NEUTRAL, "https://arweave.net/6S5a74z2s5aZrTE71nJR1a1j9x5v46mPy3MKJZMylwg"),
        SocialAvatar("022", "WireFriend", AvatarStyle.NEUTRAL, "https://arweave.net/W897b2tkHB_NulVq2Rwn4ER_MSz8CD62oT9Zvr1VHDA"),
        SocialAvatar("027", "Astrodisco", AvatarStyle.NEUTRAL, "https://arweave.net/uS4wvZn6sURMWJuwWzWnCqGaqDtZSiQQdTwci0f6hmM"),
        SocialAvatar("048", "Astronaut", AvatarStyle.NEUTRAL, "https://arweave.net/T0c0z_XEPQHy3vyXz31XB22s_6JTqHdnau8exq_I8tI"),
        SocialAvatar("051", "Polybot", AvatarStyle.NEUTRAL, "https://arweave.net/DUR8v-IugXppdMBxPdE1rDO2dZCJJ7ZgBTXSRgPJFNo"),
        SocialAvatar("058", "Shiro", AvatarStyle.NEUTRAL, "https://arweave.net/7skrWhSd_4mrqe-tiqMfCL746xu8UWghRh1dZm7irzM"),
        SocialAvatar("124", "CosmicDweller", AvatarStyle.NEUTRAL, "https://arweave.net/r3nOVHOyNuIM66TZRGhSE7_uUKYQKoRYcfLqoOOjY_k"),
        SocialAvatar("129", "CosmicPerson", AvatarStyle.NEUTRAL, "https://arweave.net/RJQUk7bmX8XaoTp-z0mWr8xDh58G6d6mvTbQJjy9jl4"),
        SocialAvatar("208", "Cyberpal", AvatarStyle.NEUTRAL, "https://arweave.net/zNTLqtifNdl38MpdNXACcvSMFVu6-s7d6pJQDm7E7Us"),
    )
}
