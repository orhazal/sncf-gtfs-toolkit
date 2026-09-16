-- MOTIS user script for output/sncf_patched.zip: trip_short_name holds the SncfBrand enum name,
-- trip_headsign the train number. Display name becomes "<brand> <train number>".
local BRANDS = {
    CAR_A_RESERVATION = 'Car à résa',
    CAR_TER = 'TER',
    ICE = 'ICE',
    INTERCITES = 'IC',
    INTERCITES_DE_NUIT = 'IC de nuit',
    LYRIA = 'TGV Lyria',
    NAVETTE = 'Navette',
    OUIGO = 'OUIGO',
    OUIGO_TRAIN_CLASSIQUE = 'OUIGO TC',
    TGV_INOUI = 'TGV INOUI',
    TRAIN_TER = 'TER',
    TRAMTRAIN = 'Tram-train',
}

function process_trip(trip)
    local brand = BRANDS[trip:get_short_name()] or trip:get_short_name()
    trip:set_display_name(brand .. ' ' .. trip:get_headsign())
end
