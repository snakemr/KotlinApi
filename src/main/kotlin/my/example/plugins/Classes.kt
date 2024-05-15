package my.example.plugins

import my.example.Address

data class Delivery(
    val track: String,
    val items: String,
    val weight: Double,
    val worth: Double,
    val origin: Address,
    val destinations: List<Address>
)

enum class Status { New, Processing, Sent, Delivered }