package com.unciv.logic.trade

import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.models.gamebasics.tile.ResourceType
import com.unciv.models.gamebasics.tr

class TradeLogic(val ourCivilization:CivilizationInfo, val otherCivilization: CivilizationInfo){

    /** Contains everything we could offer the other player, whether we've actually offered it or not */
    val ourAvailableOffers = getAvailableOffers(ourCivilization,otherCivilization)
    val theirAvailableOffers = getAvailableOffers(otherCivilization,ourCivilization)
    val currentTrade = Trade()

    fun getAvailableOffers(civInfo: CivilizationInfo, otherCivilization: CivilizationInfo): TradeOffersList {
        val offers = TradeOffersList()
        if (civInfo.isCityState() && otherCivilization.isCityState()) return offers
        if(civInfo.isAtWarWith(otherCivilization))
            offers.add(TradeOffer("Peace Treaty", TradeType.Treaty, 20))

        if(!otherCivilization.getDiplomacyManager(civInfo).hasOpenBorders
                && !otherCivilization.isCityState()
                && civInfo.tech.getTechUniques().contains("Enables Open Borders agreements")
                && otherCivilization.tech.getTechUniques().contains("Enables Open Borders agreements")) {
            val relationshipLevel = otherCivilization.getDiplomacyManager(civInfo).relationshipLevel()

            if(relationshipLevel!=RelationshipLevel.Enemy && relationshipLevel!=RelationshipLevel.Unforgivable)
                offers.add(TradeOffer("Open Borders", TradeType.Agreement, 30))
        }

        for(entry in civInfo.getCivResources().filterNot { it.key.resourceType == ResourceType.Bonus }) {
            val resourceTradeType = if(entry.key.resourceType== ResourceType.Luxury) TradeType.Luxury_Resource
            else TradeType.Strategic_Resource
            offers.add(TradeOffer(entry.key.name, resourceTradeType, 30, entry.value))
        }
        if (!civInfo.isCityState() && !otherCivilization.isCityState()) {
            for (entry in civInfo.tech.techsResearched
                    .filterNot { otherCivilization.tech.isResearched(it) }
                    .filter { otherCivilization.tech.canBeResearched(it) }) {
                offers.add(TradeOffer(entry, TradeType.Technology, 0))
            }
        }

        offers.add(TradeOffer("Gold".tr(), TradeType.Gold, 0, civInfo.gold))
        offers.add(TradeOffer("Gold per turn".tr(), TradeType.Gold_Per_Turn, 30, civInfo.getStatsForNextTurn().gold.toInt()))
        if (!civInfo.isCityState() && !otherCivilization.isCityState()) {
            for (city in civInfo.cities.filterNot { it.isCapital() })
                offers.add(TradeOffer(city.name, TradeType.City, 0))
        }

        val otherCivsWeKnow = civInfo.getKnownCivs()
                .filter { it.civName != otherCivilization.civName && !it.isBarbarianCivilization() && !it.isDefeated() }
        val civsWeKnowAndTheyDont = otherCivsWeKnow
                .filter { !otherCivilization.diplomacy.containsKey(it.civName) && !it.isDefeated() }

        if (!otherCivilization.isCityState()) {
            for (thirdCiv in civsWeKnowAndTheyDont) {
                offers.add(TradeOffer("Introduction to " + thirdCiv.civName, TradeType.Introduction, 0))
            }
        }

        if (!civInfo.isCityState() && !otherCivilization.isCityState()) {
            val civsWeBothKnow = otherCivsWeKnow
                    .filter { otherCivilization.diplomacy.containsKey(it.civName) }
            val civsWeArentAtWarWith = civsWeBothKnow
                    .filter { civInfo.getDiplomacyManager(it).diplomaticStatus == DiplomaticStatus.Peace }
            for (thirdCiv in civsWeArentAtWarWith) {
                offers.add(TradeOffer("Declare war on " + thirdCiv.civName, TradeType.WarDeclaration, 0))
            }
        }

        return offers
    }

    fun acceptTrade() {
        ourCivilization.getDiplomacyManager(otherCivilization).apply {
            trades.add(currentTrade)
            updateHasOpenBorders()
        }
        otherCivilization.getDiplomacyManager(ourCivilization).apply {
            trades.add(currentTrade.reverse())
            updateHasOpenBorders()
        }

        // instant transfers
        fun transferTrade(to: CivilizationInfo, from: CivilizationInfo, trade: Trade) {
            for (offer in trade.theirOffers) {
                if (offer.type == TradeType.Gold) {
                    to.gold += offer.amount
                    from.gold -= offer.amount
                }
                if (offer.type == TradeType.Technology) {
                    to.tech.addTechnology(offer.name)
                }
                if(offer.type== TradeType.City){
                    val city = from.cities.first { it.name==offer.name }
                    city.moveToCiv(to)
                    city.getCenterTile().getUnits().forEach { it.movementAlgs().teleportToClosestMoveableTile() }
                    to.updateViewableTiles()
                    from.updateViewableTiles()
                }
                if(offer.type== TradeType.Treaty){
                    if(offer.name=="Peace Treaty") to.getDiplomacyManager(from).makePeace()
                }
                if(offer.type==TradeType.Introduction)
                    to.meetCivilization(to.gameInfo.getCivilization(offer.name.split(" ")[2]))

                if(offer.type==TradeType.WarDeclaration){
                    val nameOfCivToDeclareWarOn = offer.name.split(' ').last()
                    from.diplomacy[nameOfCivToDeclareWarOn]!!.declareWar()
                }
            }
        }

        transferTrade(ourCivilization,otherCivilization,currentTrade)
        transferTrade(otherCivilization,ourCivilization,currentTrade.reverse())

        //Buy friendship with gold.
        if (currentTrade.theirOffers.isEmpty()) {
            for (trade in currentTrade.ourOffers) {
                if (trade.type == TradeType.Gold) {
                    otherCivilization.getDiplomacyManager(ourCivilization).influence += trade.amount / 10
                }
                if (trade.type == TradeType.Gold_Per_Turn) {
                    otherCivilization.getDiplomacyManager(ourCivilization).influence += trade.amount * trade.duration / 10
                }
            }
        }
    }
}

