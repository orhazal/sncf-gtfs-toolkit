-- MOTIS user script for output/sncf_patched.zip: trip_short_name holds the SncfBrand enum name,
-- trip_headsign the train number. Display name becomes "<brand> <train number>".
local BRANDS = {
    TGV_INOUI = 'TGV Inoui',
    OUIGO = 'OUIGO',
    LYRIA = 'TGV Lyria',
    ICE = 'ICE',
    INTERCITES = 'IC',
    INTERCITES_DE_NUIT = 'IC de nuit',
    TRAIN_TER = 'Train TER',
    OUIGO_TRAIN_CLASSIQUE = 'OUIGO TC',
    TRAMTRAIN = 'Tram-train',
    NAVETTE = 'Navette',
    CAR_TER = 'Car TER',
    CAR_A_RESERVATION = 'Car à résa',
}

function process_trip(trip)
    local brand = BRANDS[trip:get_short_name()] or trip:get_short_name()
    trip:set_display_name(brand .. ' ' .. trip:get_headsign())
end
