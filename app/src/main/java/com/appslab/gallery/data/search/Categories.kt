package com.appslab.gallery.data.search

import androidx.compose.ui.graphics.Color
import com.appslab.gallery.R
import java.text.Normalizer

/**
 * The ten categories of the original AIImageViewModel, with the same names, icons and
 * colours. [labels] are ML Kit default-model labels (lowercase) that put a photo in it.
 */
enum class SearchCategory(val title: String, val icon: Int, val color: Color, val labels: Set<String>) {
    PEOPLE(
        "People & Faces", R.drawable.people__1_, Color(0xFF0CA7C1),
        setOf("selfie", "smile", "beard", "moustache", "crowd", "team", "baby", "child", "bride", "eyelash", "hair", "tights", "sitting", "standing", "dude", "flesh", "ear", "hand", "nail", "glasses", "sunglasses", "fun", "event", "party", "dance"),
    ),
    ANIMALS(
        "Animals & Pets", R.drawable.animal, Color(0xFF00D833),
        setOf("dog", "cat", "bird", "horse", "pet", "fish", "insect", "butterfly", "cattle", "bear", "duck", "penguin", "rabbit", "turtle", "shark", "crab", "dinosaur", "lizard", "monkey", "sheep", "pomacentridae", "aquarium", "zoo", "bull", "ant", "bee", "goat", "snake"),
    ),
    VEHICLES(
        "Vehicles", R.drawable.cars, Color(0xFFEDBF00),
        setOf("car", "vehicle", "bicycle", "motorcycle", "bus", "truck", "train", "boat", "airplane", "aircraft", "helicopter", "tractor", "wheel", "tire", "limousine", "van", "sled", "scooter", "ship", "rocket"),
    ),
    FOOD(
        "Food & Drinks", R.drawable.food__1_, Color(0xFFFF3198),
        setOf("food", "dessert", "cake", "cookie", "bread", "pizza", "hot dog", "cheeseburger", "sushi", "bento", "noodle", "pasta", "fruit", "vegetable", "drink", "coffee", "tea", "wine", "beer", "juice", "cup", "lunch", "meal", "cuisine", "dish", "plate", "egg", "candy", "chocolate", "ice cream", "cola", "sandwich"),
    ),
    NATURE(
        "Nature & Landscapes", R.drawable.nature, Color(0xFF009688),
        setOf("sky", "cloud", "sunset", "sunrise", "beach", "sea", "ocean", "lake", "river", "waterfall", "mountain", "hill", "forest", "tree", "plant", "flower", "garden", "grass", "leaf", "rock", "desert", "snow", "iceberg", "himalayan", "park", "field", "rainbow", "moon", "star", "storm", "lightning", "cave", "canyon", "volcano", "swimming", "surfing", "rafting"),
    ),
    BUILDINGS(
        "Buildings & Architecture", R.drawable.building, Color(0xFF795548),
        setOf("building", "skyscraper", "house", "bridge", "tower", "church", "temple", "castle", "monument", "statue", "factory", "stadium", "roof", "room", "kitchen", "bathroom", "bedroom", "sink", "window", "door", "stairs", "column", "city", "town", "street", "road", "interior design", "shelf", "couch", "chair", "table", "desk"),
    ),
    DOCUMENTS(
        "Text & Documents", R.drawable.doc, Color(0xFF607D8B),
        setOf("paper", "poster", "menu", "receipt", "newspaper", "book", "comics", "handwriting", "letter", "font", "text", "whiteboard", "blackboard", "map", "calendar", "screenshot", "document"),
    ),
    SPORTS(
        "Sports & Activities", R.drawable.sport, Color(0xFFFF8B00),
        setOf("sports", "sport", "soccer", "football", "basketball", "tennis", "golf", "baseball", "skiing", "skateboard", "surfboard", "running", "racing", "gym", "competition", "game", "marathon", "wakeboarding", "wetsuit", "climbing", "cycling", "circus", "concert", "stage", "musical instrument", "guitar", "piano", "graduation"),
    ),
    TECHNOLOGY(
        "Technology", R.drawable.technology, Color(0xFF3F51B5),
        setOf("computer", "laptop", "mobile phone", "cell phone", "television", "monitor", "keyboard", "camera", "headphones", "microphone", "robot", "gadget", "screen", "joystick", "remote control", "speaker", "clock", "watch", "machine"),
    ),
    ART(
        "Art & Abstract", R.drawable.art, Color(0xFF1283FF),
        setOf("art", "painting", "drawing", "sculpture", "pattern", "neon", "graffiti", "porcelain", "cartoon", "illustration", "toy", "doll", "balloon", "christmas", "fireworks", "bonfire", "tattoo", "jewelry", "flag", "mosaic", "shadow"),
    );

    /** A photo belongs here when any of its labels matches, or (for PEOPLE) it has faces. */
    fun matches(labels: Collection<String>, faces: Int): Boolean =
        (this == PEOPLE && faces > 0) || labels.any { it in this.labels }
}

/** Lowercase, strip diacritics (đ→d too) so "Đà Lạt", "da lat" and "DA LAT" all match. */
fun normalizeForSearch(s: String): String {
    val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
    return decomposed.replace(Regex("\\p{Mn}+"), "").replace('đ', 'd')
}

/**
 * Vietnamese words for common ML Kit labels, so searching in Vietnamese finds
 * English labels. Keys are already normalised (no diacritics).
 */
val VietnameseSynonyms: Map<String, List<String>> = mapOf(
    "nguoi" to listOf("selfie", "smile", "crowd", "team", "face"),
    "mat" to listOf("face", "selfie", "smile"),
    "khuon mat" to listOf("face", "selfie"),
    "cuoi" to listOf("smile"),
    "tre em" to listOf("child", "baby"),
    "em be" to listOf("baby"),
    "cho" to listOf("dog"),
    "meo" to listOf("cat"),
    "chim" to listOf("bird"),
    "ca" to listOf("fish"),
    "ngua" to listOf("horse"),
    "thu cung" to listOf("pet", "dog", "cat"),
    "dong vat" to listOf("pet", "dog", "cat", "bird", "fish"),
    "xe" to listOf("car", "vehicle", "motorcycle", "bicycle"),
    "o to" to listOf("car"),
    "xe may" to listOf("motorcycle", "scooter"),
    "xe dap" to listOf("bicycle"),
    "may bay" to listOf("airplane", "aircraft"),
    "tau" to listOf("boat", "ship", "train"),
    "do an" to listOf("food", "meal", "dish", "cuisine"),
    "mon an" to listOf("food", "dish", "cuisine"),
    "banh" to listOf("cake", "bread", "cookie", "dessert"),
    "trai cay" to listOf("fruit"),
    "rau" to listOf("vegetable"),
    "ca phe" to listOf("coffee"),
    "tra" to listOf("tea"),
    "nuoc" to listOf("drink", "juice", "lake", "river", "sea"),
    "bien" to listOf("sea", "beach", "ocean"),
    "bai bien" to listOf("beach"),
    "nui" to listOf("mountain", "hill"),
    "rung" to listOf("forest", "tree"),
    "cay" to listOf("tree", "plant"),
    "hoa" to listOf("flower"),
    "troi" to listOf("sky", "cloud"),
    "may" to listOf("cloud"),
    "hoang hon" to listOf("sunset"),
    "binh minh" to listOf("sunrise"),
    "song" to listOf("river"),
    "ho" to listOf("lake"),
    "tuyet" to listOf("snow"),
    "nha" to listOf("house", "building", "room"),
    "toa nha" to listOf("building", "skyscraper"),
    "cau" to listOf("bridge"),
    "duong" to listOf("road", "street"),
    "thanh pho" to listOf("city", "skyscraper"),
    "phong" to listOf("room", "bedroom", "kitchen"),
    "giay" to listOf("paper", "document", "shoe"),
    "tai lieu" to listOf("document", "paper", "text"),
    "sach" to listOf("book"),
    "chu" to listOf("text", "font", "handwriting"),
    "the thao" to listOf("sports", "soccer", "football", "basketball"),
    "bong da" to listOf("soccer", "football"),
    "may tinh" to listOf("computer", "laptop"),
    "dien thoai" to listOf("mobile phone", "cell phone"),
    "nghe thuat" to listOf("art", "painting", "drawing"),
    "tranh" to listOf("painting", "drawing"),
    "do choi" to listOf("toy", "doll"),
    "tiec" to listOf("party", "event"),
    "dam cuoi" to listOf("bride"),
    "tot nghiep" to listOf("graduation"),
    "hoa don" to listOf("receipt"),
)
