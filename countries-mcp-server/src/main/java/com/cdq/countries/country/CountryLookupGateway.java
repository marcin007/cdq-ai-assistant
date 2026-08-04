package com.cdq.countries.country;

public interface CountryLookupGateway {

    CountryLookupResult byCommonName(String name);

    CountryLookupResult byCapital(String capital);
}
