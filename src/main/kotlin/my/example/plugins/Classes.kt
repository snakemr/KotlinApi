package my.example.plugins

import my.example.Address

data class Delivery(
    val track: String,
    val items: String,
    val weight: Double,
    val worth: Double,
    val origin: Address,
    val destinations: List<Address>,
    val status: Long?
)

data class Track(
    val status: String,
    val date: String?,
    val lat: Double?,
    val lng: Double?
)

enum class Status(val text: String) {
    Created ("Just created"),
    Processing ("Processing transaction"),
    Successful ("Transaction successful"),
    Courier ("Courier requested"),
    Ready ("Package ready for delivery"),
    Transit ("Package in transit"),
    Delivered ("Package delivered")
}