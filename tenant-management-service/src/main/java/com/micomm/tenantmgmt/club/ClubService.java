package com.micomm.tenantmgmt.club;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ClubService {

    private final ClubRepository clubRepository;

    public ClubService(ClubRepository clubRepository) {
        this.clubRepository = clubRepository;
    }

    public Club create(ClubCreateRequest request) {
        Club club = new Club();
        club.setName(request.getName());
        club.setSlug(request.getSlug());
        club.setCountry(request.getCountry());
        club.setTimezone(StringUtils.hasText(request.getTimezone()) ? request.getTimezone() : "UTC");
        club.setContactName(request.getContactName());
        club.setContactEmail(request.getContactEmail());
        club.setContactPhone(request.getContactPhone());
        club.setStatus("PENDING");
        return clubRepository.save(club);
    }

    public Club findById(UUID id) {
        return clubRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Club not found"));
    }

    public List<Club> findAll() {
        return clubRepository.findAll();
    }
}